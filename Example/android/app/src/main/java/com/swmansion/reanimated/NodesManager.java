package com.swmansion.reanimated;

import android.util.Log;
import android.util.SparseArray;

import com.facebook.react.bridge.JSApplicationIllegalArgumentException;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.UiThreadUtil;
import com.facebook.react.modules.core.ReactChoreographer;
import com.facebook.react.uimanager.GuardedFrameCallback;
import com.facebook.react.uimanager.UIImplementation;
import com.facebook.react.uimanager.UIManagerModule;
import com.facebook.react.uimanager.events.Event;
import com.facebook.react.uimanager.events.EventDispatcherListener;
import com.swmansion.reanimated.nodes.BezierNode;
import com.swmansion.reanimated.nodes.BlockNode;
import com.swmansion.reanimated.nodes.ClockNode;
import com.swmansion.reanimated.nodes.ClockOpNode;
import com.swmansion.reanimated.nodes.CondNode;
import com.swmansion.reanimated.nodes.DebugNode;
import com.swmansion.reanimated.nodes.EventNode;
import com.swmansion.reanimated.nodes.Node;
import com.swmansion.reanimated.nodes.OperatorNode;
import com.swmansion.reanimated.nodes.PropsNode;
import com.swmansion.reanimated.nodes.SetNode;
import com.swmansion.reanimated.nodes.StyleNode;
import com.swmansion.reanimated.nodes.TransformNode;
import com.swmansion.reanimated.nodes.ValueNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

public class NodesManager implements EventDispatcherListener {

  public interface OnAnimationFrame {
    void onAnimationFrame();
  }

  private final SparseArray<Node> mAnimatedNodes = new SparseArray<>();
  private final Map<String, EventNode> mEventMapping = new HashMap<>();
  private final UIImplementation mUIImplementation;
  private final ReactChoreographer mReactChoreographer;
  private final GuardedFrameCallback mChoreographerCallback;
  private final UIManagerModule.CustomEventNamesResolver mCustomEventNamesResolver;
  private final AtomicBoolean mCallbackPosted = new AtomicBoolean();

  private List<OnAnimationFrame> mFrameCallbacks = new ArrayList<>();
  private ConcurrentLinkedQueue<Event> mEventQueue = new ConcurrentLinkedQueue<>();
  private boolean mWantRunUpdates;

  public double currentFrameTimeMs;
  public final UpdateContext updateContext;

  public NodesManager(ReactContext context) {
    UIManagerModule uiManager = context.getNativeModule(UIManagerModule.class);
    updateContext = new UpdateContext();
    mUIImplementation = uiManager.getUIImplementation();
    mCustomEventNamesResolver = uiManager.getDirectEventNamesResolver();
    uiManager.getEventDispatcher().addListener(this);

    mReactChoreographer = ReactChoreographer.getInstance();
    mChoreographerCallback = new GuardedFrameCallback(context) {
      @Override
      protected void doFrameGuarded(long frameTimeNanos) {
        onAnimationFrame(frameTimeNanos);
      }
    };
  }

  public void onHostPause() {
    if (mCallbackPosted.get()) {
      stopUpdatingOnAnimationFrame();
      mCallbackPosted.set(true);
    }
  }

  public void onHostResume() {
    if (mCallbackPosted.getAndSet(false)) {
      startUpdatingOnAnimationFrame();
    }
  }

  private void startUpdatingOnAnimationFrame() {
    if (!mCallbackPosted.getAndSet(true)) {
      mReactChoreographer.postFrameCallback(
              ReactChoreographer.CallbackType.NATIVE_ANIMATED_MODULE,
              mChoreographerCallback);
    }
  }

  private void stopUpdatingOnAnimationFrame() {
    if (mCallbackPosted.getAndSet(false)) {
      mReactChoreographer.removeFrameCallback(
              ReactChoreographer.CallbackType.NATIVE_ANIMATED_MODULE,
              mChoreographerCallback);
    }
  }

  private void onAnimationFrame(long frameTimeNanos) {
    currentFrameTimeMs = frameTimeNanos / 1000000.;

    while (!mEventQueue.isEmpty()) {
      handleEvent(mEventQueue.poll());
    }

    if (!mFrameCallbacks.isEmpty()) {
      List<OnAnimationFrame> frameCallbacks = mFrameCallbacks;
      mFrameCallbacks = new ArrayList<>(frameCallbacks.size());
      for (int i = 0, size = frameCallbacks.size(); i < size; i++) {
        frameCallbacks.get(i).onAnimationFrame();
      }
    }

    if (mWantRunUpdates) {
      Node.runUpdates(updateContext);
    }

    mCallbackPosted.set(false);
    mWantRunUpdates = false;

    if (!mFrameCallbacks.isEmpty() || !mEventQueue.isEmpty()) {
      // enqueue next frame
      startUpdatingOnAnimationFrame();
    }
  }

  public @Nullable Node findNodeById(int id) {
    return mAnimatedNodes.get(id);
  }

  public void createNode(int nodeID, ReadableMap config) {
    if (mAnimatedNodes.get(nodeID) != null) {
      throw new JSApplicationIllegalArgumentException("Animated node with ID " + nodeID +
              " already exists");
    }
    String type = config.getString("type");
    final Node node;
    if ("props".equals(type)) {
      node = new PropsNode(nodeID, config, this, mUIImplementation);
    } else if ("style".equals(type)) {
      node = new StyleNode(nodeID, config, this);
    } else if ("transform".equals(type)) {
      node = new TransformNode(nodeID, config, this);
    } else if ("value".equals(type)) {
      node = new ValueNode(nodeID, config, this);
    } else if ("block".equals(type)) {
      node = new BlockNode(nodeID, config, this);
    } else if ("cond".equals(type)) {
      node = new CondNode(nodeID, config, this);
    } else if ("op".equals(type)) {
      node = new OperatorNode(nodeID, config, this);
    } else if ("set".equals(type)) {
      node = new SetNode(nodeID, config, this);
    } else if ("debug".equals(type)) {
      node = new DebugNode(nodeID, config, this);
    } else if ("clock".equals(type)) {
      node = new ClockNode(nodeID, config, this);
    } else if ("clockStart".equals(type)) {
      node = new ClockOpNode.ClockStartNode(nodeID, config, this);
    } else if ("clockStop".equals(type)) {
      node = new ClockOpNode.ClockStopNode(nodeID, config, this);
    } else if ("clockTest".equals(type)) {
      node = new ClockOpNode.ClockTestNode(nodeID, config, this);
    } else if ("call".equals(type)) {
      throw new JSApplicationIllegalArgumentException("Unsupported node type: " + type);
    } else if ("bezier".equals(type)) {
      node = new BezierNode(nodeID, config, this);
    } else if ("event".equals(type)) {
      node = new EventNode(nodeID, config, this);
    } else {
      throw new JSApplicationIllegalArgumentException("Unsupported node type: " + type);
    }
    mAnimatedNodes.put(nodeID, node);
  }

  public void dropNode(int tag) {
    mAnimatedNodes.remove(tag);
  }

  public void connectNodes(int parentID, int childID) {
    Node parentNode = mAnimatedNodes.get(parentID);
    if (parentNode == null) {
      throw new JSApplicationIllegalArgumentException("Animated node with ID " + parentID +
              " does not exists");
    }
    Node childNode = mAnimatedNodes.get(childID);
    if (childNode == null) {
      throw new JSApplicationIllegalArgumentException("Animated node with ID " + childID +
              " does not exists");
    }
    parentNode.addChild(childNode);
  }

  public void disconnectNodes(int parentID, int childID) {
    Node parentNode = mAnimatedNodes.get(parentID);
    if (parentNode == null) {
      throw new JSApplicationIllegalArgumentException("Animated node with ID " + parentID +
              " does not exists");
    }
    Node childNode = mAnimatedNodes.get(childID);
    if (childNode == null) {
      throw new JSApplicationIllegalArgumentException("Animated node with ID " + childID +
              " does not exists");
    }
    parentNode.removeChild(childNode);
  }

  public void connectNodeToView(int nodeID, int viewTag) {
    Node node = mAnimatedNodes.get(nodeID);
    if (node == null) {
      throw new JSApplicationIllegalArgumentException("Animated node with ID " + nodeID +
              " does not exists");
    }
    if (!(node instanceof PropsNode)) {
      throw new JSApplicationIllegalArgumentException("Animated node connected to view should be" +
              "of type " + PropsNode.class.getName());
    }
    ((PropsNode) node).connectToView(viewTag);
  }

  public void disconnectNodeFromView(int nodeID, int viewTag) {
    Node node = mAnimatedNodes.get(nodeID);
    if (node == null) {
      throw new JSApplicationIllegalArgumentException("Animated node with ID " + nodeID +
              " does not exists");
    }
    if (!(node instanceof PropsNode)) {
      throw new JSApplicationIllegalArgumentException("Animated node connected to view should be" +
              "of type " + PropsNode.class.getName());
    }
    ((PropsNode) node).disconnectFromView(viewTag);
  }

  public void attachEvent(int viewTag, String eventName, int eventNodeID) {
    String key = viewTag + eventName;

    EventNode node = (EventNode) mAnimatedNodes.get(eventNodeID);
    if (node == null) {
      throw new JSApplicationIllegalArgumentException("Event node " + eventNodeID + " does not exists");
    }
    if (mEventMapping.containsKey(key)) {
      throw new JSApplicationIllegalArgumentException("Event handler already set for the given view and event type");
    }

    mEventMapping.put(key, node);
  }

  public void detachEvent(int viewTag, String eventName, int eventNodeID) {
    String key = viewTag + eventName;
    mEventMapping.remove(key);
  }

  public void postRunUpdatesAfterAnimation() {
    mWantRunUpdates = true;
    startUpdatingOnAnimationFrame();
  }

  public void postOnAnimation(OnAnimationFrame onAnimationFrame) {
    mFrameCallbacks.add(onAnimationFrame);
    startUpdatingOnAnimationFrame();
  }

  @Override
  public void onEventDispatch(Event event) {
    // Events can be dispatched from any thread so we have to make sure handleEvent is run from the
    // UI thread.
    if (UiThreadUtil.isOnUiThread()) {
      handleEvent(event);
    } else {
      mEventQueue.offer(event);
      startUpdatingOnAnimationFrame();
    }
  }

  private void handleEvent(Event event) {
    if (!mEventMapping.isEmpty()) {
      // If the event has a different name in native convert it to it's JS name.
      String eventName = mCustomEventNamesResolver.resolveCustomEventName(event.getEventName());
      int viewTag = event.getViewTag();
      String key = viewTag + eventName;
      EventNode node = mEventMapping.get(key);
      if (node != null) {
        event.dispatch(node);
      }
    }
  }
}
