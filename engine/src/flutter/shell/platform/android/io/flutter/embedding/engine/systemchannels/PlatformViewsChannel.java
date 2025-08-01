// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.embedding.engine.systemchannels;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.flutter.Log;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.StandardMethodCodec;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * System channel that sends 2-way communication between Flutter and Android to facilitate embedding
 * of Android Views within a Flutter application.
 *
 * <p>Register a {@link PlatformViewsHandler} to implement the Android side of this channel.
 */
public class PlatformViewsChannel {
  private static final String TAG = "PlatformViewsChannel";

  private final MethodChannel channel;
  private PlatformViewsHandler handler;

  public void invokeViewFocused(int viewId) {
    if (channel == null) {
      return;
    }
    channel.invokeMethod("viewFocused", viewId);
  }

  private static String detailedExceptionString(Exception exception) {
    return Log.getStackTraceString(exception);
  }

  private final MethodChannel.MethodCallHandler parsingHandler =
      new MethodChannel.MethodCallHandler() {
        @Override
        public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
          // If there is no handler to respond to this message then we don't need to
          // parse it. Return.
          if (handler == null) {
            return;
          }

          Log.v(TAG, "Received '" + call.method + "' message.");
          switch (call.method) {
            case "create":
              create(call, result);
              break;
            case "dispose":
              dispose(call, result);
              break;
            case "resize":
              resize(call, result);
              break;
            case "offset":
              offset(call, result);
              break;
            case "touch":
              touch(call, result);
              break;
            case "setDirection":
              setDirection(call, result);
              break;
            case "clearFocus":
              clearFocus(call, result);
              break;
            case "synchronizeToNativeViewHierarchy":
              synchronizeToNativeViewHierarchy(call, result);
              break;
            default:
              result.notImplemented();
          }
        }

        private void create(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {

          final Map<String, Object> createArgs = call.arguments();
          // TODO(egarciad): Remove the "hybrid" case.
          final boolean usesPlatformViewLayer =
              createArgs.containsKey("hybrid") && (boolean) createArgs.get("hybrid");
          final ByteBuffer additionalParams =
              createArgs.containsKey("params")
                  ? ByteBuffer.wrap((byte[]) createArgs.get("params"))
                  : null;

          try {
            // TODO(gmackall): Enable hcpp path in a follow up PR to
            //                 https://github.com/flutter/flutter/pull/170553/.
            //                 with a new more externally friendly flag name.
            //            if (handler.isHcppEnabled()) {
            //              final PlatformViewCreationRequest request =
            //                  PlatformViewCreationRequest.createHCPPRequest(
            //                      (int) createArgs.get("id"),
            //                      (String) createArgs.get("viewType"),
            //                      (int) createArgs.get("direction"),
            //                      additionalParams);
            //              handler.createPlatformViewHcpp(request);
            //              result.success(null);
            //              return;
            //            }
            if (usesPlatformViewLayer) {
              final PlatformViewCreationRequest request =
                  PlatformViewCreationRequest.createHybridCompositionRequest(
                      (int) createArgs.get("id"),
                      (String) createArgs.get("viewType"),
                      (int) createArgs.get("direction"),
                      additionalParams);
              handler.createForPlatformViewLayer(request);
              result.success(null);
            } else {
              final boolean hybridFallback =
                  createArgs.containsKey("hybridFallback")
                      && (boolean) createArgs.get("hybridFallback");
              final PlatformViewCreationRequest request =
                  PlatformViewCreationRequest.createTLHCWithFallbackRequest(
                      (int) createArgs.get("id"),
                      (String) createArgs.get("viewType"),
                      createArgs.containsKey("top") ? (double) createArgs.get("top") : 0.0,
                      createArgs.containsKey("left") ? (double) createArgs.get("left") : 0.0,
                      (double) createArgs.get("width"),
                      (double) createArgs.get("height"),
                      (int) createArgs.get("direction"),
                      hybridFallback,
                      additionalParams);
              long textureId = handler.createForTextureLayer(request);
              if (textureId == PlatformViewsHandler.NON_TEXTURE_FALLBACK) {
                if (!hybridFallback) {
                  throw new AssertionError(
                      "Platform view attempted to fall back to hybrid mode when not requested.");
                }
                // A fallback to hybrid mode is indicated with a null texture ID.
                result.success(null);
              } else {
                result.success(textureId);
              }
            }
          } catch (IllegalStateException exception) {
            result.error("error", detailedExceptionString(exception), null);
          }
        }

        private void dispose(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
          Map<String, Object> disposeArgs = call.arguments();
          int viewId = (int) disposeArgs.get("id");

          try {
            handler.dispose(viewId);
            result.success(null);
          } catch (IllegalStateException exception) {
            result.error("error", detailedExceptionString(exception), null);
          }
        }

        private void resize(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
          Map<String, Object> resizeArgs = call.arguments();
          PlatformViewResizeRequest resizeRequest =
              new PlatformViewResizeRequest(
                  (int) resizeArgs.get("id"),
                  (double) resizeArgs.get("width"),
                  (double) resizeArgs.get("height"));
          try {
            handler.resize(
                resizeRequest,
                (PlatformViewBufferSize bufferSize) -> {
                  if (bufferSize == null) {
                    result.error("error", "Failed to resize the platform view", null);
                  } else {
                    final Map<String, Object> response = new HashMap<>();
                    response.put("width", (double) bufferSize.width);
                    response.put("height", (double) bufferSize.height);
                    result.success(response);
                  }
                });
          } catch (IllegalStateException exception) {
            result.error("error", detailedExceptionString(exception), null);
          }
        }

        private void offset(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
          Map<String, Object> offsetArgs = call.arguments();
          try {
            handler.offset(
                (int) offsetArgs.get("id"),
                (double) offsetArgs.get("top"),
                (double) offsetArgs.get("left"));
            result.success(null);
          } catch (IllegalStateException exception) {
            result.error("error", detailedExceptionString(exception), null);
          }
        }

        private void touch(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
          List<Object> args = call.arguments();
          PlatformViewTouch touch =
              new PlatformViewTouch(
                  (int) args.get(0),
                  (Number) args.get(1),
                  (Number) args.get(2),
                  (int) args.get(3),
                  (int) args.get(4),
                  args.get(5),
                  args.get(6),
                  (int) args.get(7),
                  (int) args.get(8),
                  (float) (double) args.get(9),
                  (float) (double) args.get(10),
                  (int) args.get(11),
                  (int) args.get(12),
                  (int) args.get(13),
                  (int) args.get(14),
                  ((Number) args.get(15)).longValue());

          try {
            handler.onTouch(touch);
            result.success(null);
          } catch (IllegalStateException exception) {
            result.error("error", detailedExceptionString(exception), null);
          }
        }

        private void setDirection(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
          Map<String, Object> setDirectionArgs = call.arguments();
          int newDirectionViewId = (int) setDirectionArgs.get("id");
          int direction = (int) setDirectionArgs.get("direction");

          try {
            handler.setDirection(newDirectionViewId, direction);
            result.success(null);
          } catch (IllegalStateException exception) {
            result.error("error", detailedExceptionString(exception), null);
          }
        }

        private void clearFocus(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
          int viewId = call.arguments();
          try {
            handler.clearFocus(viewId);
            result.success(null);
          } catch (IllegalStateException exception) {
            result.error("error", detailedExceptionString(exception), null);
          }
        }

        private void synchronizeToNativeViewHierarchy(
            @NonNull MethodCall call, @NonNull MethodChannel.Result result) {
          boolean yes = call.arguments();
          try {
            handler.synchronizeToNativeViewHierarchy(yes);
            result.success(null);
          } catch (IllegalStateException exception) {
            result.error("error", detailedExceptionString(exception), null);
          }
        }
      };

  /**
   * Constructs a {@code PlatformViewsChannel} that connects Android to the Dart code running in
   * {@code dartExecutor}.
   *
   * <p>The given {@code dartExecutor} is permitted to be idle or executing code.
   *
   * <p>See {@link DartExecutor}.
   */
  public PlatformViewsChannel(@NonNull DartExecutor dartExecutor) {
    channel =
        new MethodChannel(dartExecutor, "flutter/platform_views", StandardMethodCodec.INSTANCE);
    channel.setMethodCallHandler(parsingHandler);
  }

  /**
   * Sets the {@link PlatformViewsHandler} which receives all events and requests that are parsed
   * from the underlying platform views channel.
   */
  public void setPlatformViewsHandler(@Nullable PlatformViewsHandler handler) {
    this.handler = handler;
  }

  /**
   * Handler that receives platform view messages sent from Flutter to Android through a given
   * {@link PlatformViewsChannel}.
   *
   * <p>To register a {@code PlatformViewsHandler} with a {@link PlatformViewsChannel}, see {@link
   * PlatformViewsChannel#setPlatformViewsHandler(PlatformViewsHandler)}.
   */
  public interface PlatformViewsHandler {
    /*
     * The ID returned by {@code createForTextureLayer} to indicate that the requested texture mode
     * was not available and the view creation fell back to {@code PlatformViewLayer} mode.
     *
     * This can only be returned if the {@code PlatformViewCreationRequest} sets
     * {@code TEXTURE_WITH_HYBRID_FALLBACK} as the requested display mode.
     */
    static final long NON_TEXTURE_FALLBACK = -2;

    /**
     * The Flutter application would like to display a new Android {@code View}, i.e., platform
     * view.
     *
     * <p>The Android View is added to the view hierarchy. This view is rendered in the Flutter
     * framework by a PlatformViewLayer.
     *
     * @param request The metadata sent from the framework.
     */
    void createForPlatformViewLayer(@NonNull PlatformViewCreationRequest request);

    /** Returns true if creation of HC++ platform views is currently supported. */
    boolean isHcppEnabled();

    void createPlatformViewHcpp(@NonNull PlatformViewCreationRequest request);

    /**
     * The Flutter application would like to display a new Android {@code View}, i.e., platform
     * view.
     *
     * <p>The Android View is added to the view hierarchy. This view is rendered in the Flutter
     * framework by a TextureLayer.
     *
     * @param request The metadata sent from the framework.
     * @return The texture ID.
     */
    long createForTextureLayer(@NonNull PlatformViewCreationRequest request);

    /** The Flutter application would like to dispose of an existing Android {@code View}. */
    void dispose(int viewId);

    /**
     * The Flutter application would like to resize an existing Android {@code View}.
     *
     * @param request The request to resize the platform view.
     * @param onComplete Once the resize is completed, this is the handler to notify the size of the
     *     platform view buffer.
     */
    void resize(
        @NonNull PlatformViewResizeRequest request, @NonNull PlatformViewBufferResized onComplete);

    /**
     * The Flutter application would like to change the offset of an existing Android {@code View}.
     */
    void offset(int viewId, double top, double left);

    /**
     * The user touched a platform view within Flutter.
     *
     * <p>Touch data is reported in {@code touch}.
     */
    void onTouch(@NonNull PlatformViewTouch touch);

    /**
     * The Flutter application would like to change the layout direction of an existing Android
     * {@code View}, i.e., platform view.
     */
    // TODO(mattcarroll): Introduce an annotation for @TextureId
    void setDirection(int viewId, int direction);

    /** Clears the focus from the platform view with a give id if it is currently focused. */
    void clearFocus(int viewId);

    /**
     * Whether the render surface of {@code FlutterView} should be converted to a {@code
     * FlutterImageView} when a {@code PlatformView} is added.
     *
     * <p>This is done to syncronize the rendering of the PlatformView and the FlutterView. Defaults
     * to true.
     */
    void synchronizeToNativeViewHierarchy(boolean yes);
  }

  /** Request sent from Flutter to resize a platform view. */
  public static class PlatformViewResizeRequest {
    /** The ID of the platform view as seen by the Flutter side. */
    public final int viewId;

    /** The new density independent width to display the platform view. */
    public final double newLogicalWidth;

    /** The new density independent height to display the platform view. */
    public final double newLogicalHeight;

    public PlatformViewResizeRequest(int viewId, double newLogicalWidth, double newLogicalHeight) {
      this.viewId = viewId;
      this.newLogicalWidth = newLogicalWidth;
      this.newLogicalHeight = newLogicalHeight;
    }
  }

  /** The platform view buffer size. */
  public static class PlatformViewBufferSize {
    /** The width of the screen buffer. */
    public final int width;

    /** The height of the screen buffer. */
    public final int height;

    public PlatformViewBufferSize(int width, int height) {
      this.width = width;
      this.height = height;
    }
  }

  /** Allows to notify when a platform view buffer has been resized. */
  public interface PlatformViewBufferResized {
    void run(@Nullable PlatformViewBufferSize bufferSize);
  }
}
