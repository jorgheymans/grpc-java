/*
 * Copyright 2018, gRPC Authors All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.android;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.os.Build;
import android.util.Log;
import com.google.common.annotations.VisibleForTesting;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ConnectivityState;
import io.grpc.ExperimentalApi;
import io.grpc.ForwardingChannelBuilder;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.internal.GrpcUtil;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * Builds a {@link ManagedChannel} that, when provided with a {@link Context}, will automatically
 * monitor the Android device's network state to smoothly handle intermittent network failures.
 *
 * <p>Requires the Android ACCESS_NETWORK_STATE permission.
 *
 * @since 1.12.0
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/4056")
public final class AndroidChannelBuilder extends ForwardingChannelBuilder<AndroidChannelBuilder> {

  private static final String LOG_TAG = "AndroidChannelBuilder";

  @Nullable private static final Class<?> OKHTTP_CHANNEL_BUILDER_CLASS = findOkHttp();

  private static final Class<?> findOkHttp() {
    try {
      return Class.forName("io.grpc.okhttp.OkHttpChannelBuilder");
    } catch (ClassNotFoundException e) {
      return null;
    }
  }

  private final ManagedChannelBuilder delegateBuilder;

  @Nullable private Context context;

  public static final AndroidChannelBuilder forTarget(String target) {
    return new AndroidChannelBuilder(target);
  }

  public static AndroidChannelBuilder forAddress(String name, int port) {
    return forTarget(GrpcUtil.authorityFromHostAndPort(name, port));
  }

  private AndroidChannelBuilder(String target) {
    if (OKHTTP_CHANNEL_BUILDER_CLASS == null) {
      throw new UnsupportedOperationException("No ManagedChannelBuilder found on the classpath");
    }
    try {
      delegateBuilder =
          (ManagedChannelBuilder)
              OKHTTP_CHANNEL_BUILDER_CLASS
                  .getMethod("forTarget", String.class)
                  .invoke(null, target);
    } catch (Exception e) {
      throw new RuntimeException("Failed to create ManagedChannelBuilder", e);
    }
  }

  /** Enables automatic monitoring of the device's network state. */
  public AndroidChannelBuilder context(Context context) {
    this.context = context;
    return this;
  }

  @Override
  protected ManagedChannelBuilder<?> delegate() {
    return delegateBuilder;
  }

  @Override
  public ManagedChannel build() {
    return new AndroidChannel(delegateBuilder.build(), context);
  }

  /**
   * Wraps an OkHttp channel and handles invoking the appropriate methods (e.g., {@link
   * ManagedChannel#resetConnectBackoff}) when the device network state changes.
   */
  @VisibleForTesting
  static final class AndroidChannel extends ManagedChannel {

    private final ManagedChannel delegate;

    @Nullable private final Context context;
    @Nullable private final ConnectivityManager connectivityManager;

    private final Object lock = new Object();

    @GuardedBy("lock")
    private Runnable unregisterRunnable;

    @VisibleForTesting
    AndroidChannel(final ManagedChannel delegate, @Nullable Context context) {
      this.delegate = delegate;
      this.context = context;

      if (context != null) {
        connectivityManager =
            (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        configureNetworkMonitoring();
      } else {
        connectivityManager = null;
      }
    }

    @GuardedBy("lock")
    private void configureNetworkMonitoring() {
      // Eagerly check current network state to verify app has required permissions
      NetworkInfo currentNetwork;
      try {
        currentNetwork = connectivityManager.getActiveNetworkInfo();
      } catch (SecurityException e) {
        Log.w(
            LOG_TAG,
            "Failed to configure network monitoring. Does app have ACCESS_NETWORK_STATE"
                + " permission?",
            e);
        return;
      }

      // Android N added the registerDefaultNetworkCallback API to listen to changes in the device's
      // default network. For earlier Android API levels, use the BroadcastReceiver API.
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && connectivityManager != null) {
        // The connection status may change before registration of the listener is complete, but
        // this will at worst result in invoking resetConnectBackoff() instead of enterIdle() (or
        // vice versa) on the first network change.
        boolean isConnected = currentNetwork != null && currentNetwork.isConnected();

        final DefaultNetworkCallback defaultNetworkCallback =
            new DefaultNetworkCallback(isConnected);
        connectivityManager.registerDefaultNetworkCallback(defaultNetworkCallback);
        unregisterRunnable =
            new Runnable() {
              @TargetApi(Build.VERSION_CODES.LOLLIPOP)
              @Override
              public void run() {
                connectivityManager.unregisterNetworkCallback(defaultNetworkCallback);
              }
            };
      } else {
        final NetworkReceiver networkReceiver = new NetworkReceiver();
        IntentFilter networkIntentFilter =
            new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        context.registerReceiver(networkReceiver, networkIntentFilter);
        unregisterRunnable =
            new Runnable() {
              @TargetApi(Build.VERSION_CODES.LOLLIPOP)
              @Override
              public void run() {
                context.unregisterReceiver(networkReceiver);
              }
            };
      }
    }

    private void unregisterNetworkListener() {
      synchronized (lock) {
        if (unregisterRunnable != null) {
          unregisterRunnable.run();
          unregisterRunnable = null;
        }
      }
    }

    @Override
    public ManagedChannel shutdown() {
      unregisterNetworkListener();
      return delegate.shutdown();
    }

    @Override
    public boolean isShutdown() {
      return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
      return delegate.isTerminated();
    }

    @Override
    public ManagedChannel shutdownNow() {
      unregisterNetworkListener();
      return delegate.shutdownNow();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(
        MethodDescriptor<RequestT, ResponseT> methodDescriptor, CallOptions callOptions) {
      return delegate.newCall(methodDescriptor, callOptions);
    }

    @Override
    public String authority() {
      return delegate.authority();
    }

    @Override
    public ConnectivityState getState(boolean requestConnection) {
      return delegate.getState(requestConnection);
    }

    @Override
    public void notifyWhenStateChanged(ConnectivityState source, Runnable callback) {
      delegate.notifyWhenStateChanged(source, callback);
    }

    @Override
    public void resetConnectBackoff() {
      delegate.resetConnectBackoff();
    }

    @Override
    public void enterIdle() {
      delegate.enterIdle();
    }

    /** Respond to changes in the default network. Only used on API levels 24+. */
    @TargetApi(Build.VERSION_CODES.N)
    private class DefaultNetworkCallback extends ConnectivityManager.NetworkCallback {
      private boolean isConnected = false;

      private DefaultNetworkCallback(boolean isConnected) {
        this.isConnected = isConnected;
      }

      @Override
      public void onAvailable(Network network) {
        if (isConnected) {
          delegate.enterIdle();
        } else {
          delegate.resetConnectBackoff();
        }
        isConnected = true;
      }

      @Override
      public void onLost(Network network) {
        isConnected = false;
      }
    }

    /** Respond to network changes. Only used on API levels < 24. */
    private class NetworkReceiver extends BroadcastReceiver {
      private boolean isConnected = false;

      @Override
      public void onReceive(Context context, Intent intent) {
        ConnectivityManager conn =
            (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = conn.getActiveNetworkInfo();
        boolean wasConnected = isConnected;
        isConnected = networkInfo != null && networkInfo.isConnected();
        if (isConnected && !wasConnected) {
          delegate.resetConnectBackoff();
        }
      }
    }
  }
}
