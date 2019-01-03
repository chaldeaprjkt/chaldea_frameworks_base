/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.media;

import static android.media.MediaConstants.KEY_ALLOWED_COMMANDS;
import static android.media.MediaConstants.KEY_PACKAGE_NAME;
import static android.media.MediaConstants.KEY_PID;
import static android.media.MediaConstants.KEY_SESSION2_STUB;
import static android.media.Session2Command.RESULT_ERROR_UNKNOWN_ERROR;
import static android.media.Session2Command.RESULT_INFO_SKIPPED;
import static android.media.Session2Token.TYPE_SESSION;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaSessionManager;
import android.media.session.MediaSessionManager.RemoteUserInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.os.ResultReceiver;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Allows a media app to expose its transport controls and playback information in a process to
 * other processes including the Android framework and other apps.
 * <p>
 * This API is not generally intended for third party application developers.
 * Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
 * <a href="{@docRoot}reference/androidx/media2/package-summary.html">Media2 Library</a>
 * for consistent behavior across all devices.
 * @hide
 */
public class MediaSession2 implements AutoCloseable {
    static final String TAG = "MediaSession";
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // Note: This checks the uniqueness of a session ID only in a single process.
    // When the framework becomes able to check the uniqueness, this logic should be removed.
    //@GuardedBy("MediaSession.class")
    private static final List<String> SESSION_ID_LIST = new ArrayList<>();

    @SuppressWarnings("WeakerAccess") /* synthetic access */
    final Object mLock = new Object();
    //@GuardedBy("mLock")
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    final Map<Controller2Link, ControllerInfo> mConnectedControllers = new HashMap<>();

    @SuppressWarnings("WeakerAccess") /* synthetic access */
    final Context mContext;
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    final Executor mCallbackExecutor;
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    final SessionCallback mCallback;
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    final Session2Link mSessionStub;

    private final String mSessionId;
    private final PendingIntent mSessionActivity;
    private final Session2Token mSessionToken;
    private final MediaSessionManager mSessionManager;
    private final Handler mResultHandler;

    //@GuardedBy("mLock")
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    private boolean mClosed;

    MediaSession2(@NonNull Context context, @NonNull String id, PendingIntent sessionActivity,
            @NonNull Executor callbackExecutor, @NonNull SessionCallback callback) {
        synchronized (MediaSession2.class) {
            if (SESSION_ID_LIST.contains(id)) {
                throw new IllegalStateException("Session ID must be unique. ID=" + id);
            }
            SESSION_ID_LIST.add(id);
        }

        mContext = context;
        mSessionId = id;
        mSessionActivity = sessionActivity;
        mCallbackExecutor = callbackExecutor;
        mCallback = callback;
        mSessionStub = new Session2Link(this);
        mSessionToken = new Session2Token(Process.myUid(), TYPE_SESSION, context.getPackageName(),
                mSessionStub);
        mSessionManager = (MediaSessionManager) mContext.getSystemService(
                Context.MEDIA_SESSION_SERVICE);
        // NOTE: mResultHandler uses main looper, so this MUST NOT be blocked.
        mResultHandler = new Handler(context.getMainLooper());
    }

    @Override
    public void close() {
        try {
            synchronized (MediaSession2.class) {
                SESSION_ID_LIST.remove(mSessionId);
            }
            Collection<ControllerInfo> controllerInfos;
            synchronized (mLock) {
                controllerInfos = mConnectedControllers.values();
                mConnectedControllers.clear();
                mClosed = true;
            }
            for (ControllerInfo info : controllerInfos) {
                info.notifyDisconnected();
            }
        } catch (Exception e) {
            // Should not be here.
        }
    }

    /**
     * Returns the session ID
     */
    @NonNull
    public String getSessionId() {
        return mSessionId;
    }

    /**
     * Returns the {@link Session2Token} for creating {@link MediaController2}.
     */
    @NonNull
    public Session2Token getSessionToken() {
        return mSessionToken;
    }

    /**
     * Broadcasts a session command to all the connected controllers
     * <p>
     * @param command the session command
     * @param args optional arguments
     */
    public void broadcastSessionCommand(@NonNull Session2Command command, @Nullable Bundle args) {
        if (command == null) {
            throw new IllegalArgumentException("command shouldn't be null");
        }
        Collection<ControllerInfo> controllerInfos;
        synchronized (mLock) {
            controllerInfos = mConnectedControllers.values();
        }
        for (ControllerInfo controller : controllerInfos) {
            controller.sendSessionCommand(command, args, null);
        }
    }

    /**
     * Sends a session command to a specific controller
     * <p>
     * @param controller the controller to get the session command
     * @param command the session command
     * @param args optional arguments
     * @return a token which will be sent together in {@link SessionCallback#onCommandResult}
     *     when its result is received.
     */
    public Object sendSessionCommand(@NonNull ControllerInfo controller,
            @NonNull Session2Command command, @Nullable Bundle args) {
        if (controller == null) {
            throw new IllegalArgumentException("controller shouldn't be null");
        }
        if (command == null) {
            throw new IllegalArgumentException("command shouldn't be null");
        }
        ResultReceiver resultReceiver = new ResultReceiver(mResultHandler) {
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                controller.receiveCommandResult(this);
                mCallbackExecutor.execute(() -> {
                    mCallback.onCommandResult(MediaSession2.this, controller, this,
                            command, new Session2Command.Result(resultCode, resultData));
                });
            }
        };
        controller.sendSessionCommand(command, args, resultReceiver);
        return resultReceiver;
    }

    /**
     * Cancels the session command previously sent.
     *
     * @param controller the controller to get the session command
     * @param token the token which is returned from {@link #sendSessionCommand}.
     */
    public void cancelSessionCommand(ControllerInfo controller, Object token) {
        if (token == null) {
            throw new IllegalArgumentException("token shouldn't be null");
        }
        controller.cancelSessionCommand(token);
    }

    boolean isClosed() {
        synchronized (mLock) {
            return mClosed;
        }
    }

    // Called by Session2Link.onConnect
    void onConnect(final Controller2Link controller, int seq, Bundle connectionRequest) {
        if (controller == null || connectionRequest == null) {
            return;
        }
        final int uid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        final long token = Binder.clearCallingIdentity();
        // Binder.getCallingPid() can be 0 for an oneway call from the remote process.
        // If it's the case, use PID from the ConnectionRequest.
        final int pid = (callingPid != 0) ? callingPid : connectionRequest.getInt(KEY_PID);
        final String pkg = connectionRequest.getString(KEY_PACKAGE_NAME);
        try {
            RemoteUserInfo remoteUserInfo = new RemoteUserInfo(pkg, pid, uid);
            final ControllerInfo controllerInfo = new ControllerInfo(remoteUserInfo,
                    mSessionManager.isTrustedForMediaControl(remoteUserInfo), controller);
            mCallbackExecutor.execute(() -> {
                if (isClosed()) {
                    return;
                }
                controllerInfo.mAllowedCommands =
                        mCallback.onConnect(MediaSession2.this, controllerInfo);
                // Don't reject connection for the request from trusted app.
                // Otherwise server will fail to retrieve session's information to dispatch
                // media keys to.
                boolean accept =
                        controllerInfo.mAllowedCommands != null || controllerInfo.isTrusted();
                if (accept) {
                    if (controllerInfo.mAllowedCommands == null) {
                        // For trusted apps, send non-null allowed commands to keep
                        // connection.
                        controllerInfo.mAllowedCommands = new Session2CommandGroup();
                    }
                    if (DEBUG) {
                        Log.d(TAG, "Accepting connection: " + controllerInfo);
                    }
                    synchronized (mLock) {
                        if (mConnectedControllers.containsKey(controller)) {
                            Log.w(TAG, "Controller " + controllerInfo + " has sent connection"
                                    + " request multiple times");
                        }
                        mConnectedControllers.put(controller, controllerInfo);
                    }
                    // If connection is accepted, notify the current state to the controller.
                    // It's needed because we cannot call synchronous calls between
                    // session/controller.
                    Bundle connectionResult = new Bundle();
                    connectionResult.putParcelable(KEY_SESSION2_STUB, mSessionStub);
                    connectionResult.putParcelable(KEY_ALLOWED_COMMANDS,
                            controllerInfo.mAllowedCommands);

                    // Double check if session is still there, because close() can be called in
                    // another thread.
                    if (isClosed()) {
                        return;
                    }
                    controllerInfo.notifyConnected(connectionResult);
                } else {
                    if (DEBUG) {
                        Log.d(TAG, "Rejecting connection, controllerInfo=" + controllerInfo);
                    }
                    controllerInfo.notifyDisconnected();
                }
            });
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    // Called by Session2Link.onDisconnect
    void onDisconnect(final Controller2Link controller, int seq) {
        if (controller == null) {
            return;
        }
        final ControllerInfo controllerInfo;
        synchronized (mLock) {
            controllerInfo = mConnectedControllers.get(controller);
        }
        if (controllerInfo == null) {
            return;
        }

        final long token = Binder.clearCallingIdentity();
        try {
            mCallbackExecutor.execute(() -> {
                mCallback.onDisconnected(MediaSession2.this, controllerInfo);
            });
            mConnectedControllers.remove(controller);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    // Called by Session2Link.onSessionCommand
    void onSessionCommand(final Controller2Link controller, final int seq,
            final Session2Command command, final Bundle args,
            @Nullable ResultReceiver resultReceiver) {
        if (controller == null) {
            return;
        }
        final ControllerInfo controllerInfo;
        synchronized (mLock) {
            controllerInfo = mConnectedControllers.get(controller);
        }
        if (controllerInfo == null) {
            return;
        }

        // TODO: check allowed commands.
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                controllerInfo.addRequestedCommandSeqNumber(seq);
            }

            mCallbackExecutor.execute(() -> {
                if (!controllerInfo.removeRequestedCommandSeqNumber(seq)) {
                    resultReceiver.send(RESULT_INFO_SKIPPED, null);
                    return;
                }
                Session2Command.Result result = mCallback.onSessionCommand(
                        MediaSession2.this, controllerInfo, command, args);
                if (resultReceiver != null) {
                    if (result == null) {
                        throw new RuntimeException("onSessionCommand shouldn't return null");
                    } else {
                        resultReceiver.send(result.getResultCode(), result.getResultData());
                    }
                }
            });
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    // Called by Session2Link.onCancelCommand
    void onCancelCommand(final Controller2Link controller, final int seq) {
        final ControllerInfo controllerInfo;
        synchronized (mLock) {
            controllerInfo = mConnectedControllers.get(controller);
        }
        if (controllerInfo == null) {
            return;
        }

        final long token = Binder.clearCallingIdentity();
        try {
            controllerInfo.removeRequestedCommandSeqNumber(seq);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Builder for {@link MediaSession2}.
     * <p>
     * Any incoming event from the {@link MediaController2} will be handled on the callback
     * executor. If it's not set, {@link Context#getMainExecutor()} will be used by default.
     */
    public static final class Builder {
        private Context mContext;
        private String mId;
        private PendingIntent mSessionActivity;
        private Executor mCallbackExecutor;
        private SessionCallback mCallback;

        /**
         * Creates a builder for {@link MediaSession2}.
         *
         * @param context Context
         * @throws IllegalArgumentException if context is {@code null}.
         */
        public Builder(@NonNull Context context) {
            if (context == null) {
                throw new IllegalArgumentException("context shouldn't be null");
            }
            mContext = context;
        }

        /**
         * Set an intent for launching UI for this Session. This can be used as a
         * quick link to an ongoing media screen. The intent should be for an
         * activity that may be started using {@link Context#startActivity(Intent)}.
         *
         * @param pi The intent to launch to show UI for this session.
         * @return The Builder to allow chaining
         */
        @NonNull
        public Builder setSessionActivity(@Nullable PendingIntent pi) {
            mSessionActivity = pi;
            return this;
        }

        /**
         * Set ID of the session. If it's not set, an empty string will be used to create a session.
         * <p>
         * Use this if and only if your app supports multiple playback at the same time and also
         * wants to provide external apps to have finer controls of them.
         *
         * @param id id of the session. Must be unique per package.
         * @throws IllegalArgumentException if id is {@code null}.
         * @return The Builder to allow chaining
         */
        @NonNull
        public Builder setId(@NonNull String id) {
            if (id == null) {
                throw new IllegalArgumentException("id shouldn't be null");
            }
            mId = id;
            return this;
        }

        /**
         * Set callback for the session and its executor.
         *
         * @param executor callback executor
         * @param callback session callback.
         * @return The Builder to allow chaining
         */
        @NonNull
        public Builder setSessionCallback(@NonNull Executor executor,
                @NonNull SessionCallback callback) {
            mCallbackExecutor = executor;
            mCallback = callback;
            return this;
        }

        /**
         * Build {@link MediaSession2}.
         *
         * @return a new session
         * @throws IllegalStateException if the session with the same id is already exists for the
         *      package.
         */
        @NonNull
        public MediaSession2 build() {
            if (mCallbackExecutor == null) {
                mCallbackExecutor = mContext.getMainExecutor();
            }
            if (mCallback == null) {
                mCallback = new SessionCallback() {};
            }
            if (mId == null) {
                mId = "";
            }
            MediaSession2 session2 = new MediaSession2(mContext, mId, mSessionActivity,
                    mCallbackExecutor, mCallback);

            // Notify framework about the newly create session after the constructor is finished.
            // Otherwise, framework may access the session before the initialization is finished.
            try {
                MediaSessionManager manager = (MediaSessionManager) mContext.getSystemService(
                        Context.MEDIA_SESSION_SERVICE);
                manager.notifySession2Created(session2.getSessionToken());
            } catch (Exception e) {
                session2.close();
                throw e;
            }

            return session2;
        }
    }

    /**
     * Information of a controller.
     * <p>
     * This API is not generally intended for third party application developers.
     */
    public static final class ControllerInfo {
        private final RemoteUserInfo mRemoteUserInfo;
        private final boolean mIsTrusted;
        private final Controller2Link mControllerBinder;
        private final Object mLock = new Object();
        //@GuardedBy("mLock")
        private int mNextSeqNumber;
        //@GuardedBy("mLock")
        private ArrayMap<ResultReceiver, Integer> mPendingCommands;
        //@GuardedBy("mLock")
        private ArraySet<Integer> mRequestedCommandSeqNumbers;

        @SuppressWarnings("WeakerAccess") /* synthetic access */
        Session2CommandGroup mAllowedCommands;

        /**
         * @param remoteUserInfo remote user info
         * @param trusted {@code true} if trusted, {@code false} otherwise
         * @param controllerBinder Controller2Link for the connected controller.
         */
        ControllerInfo(@NonNull RemoteUserInfo remoteUserInfo, boolean trusted,
                @Nullable Controller2Link controllerBinder) {
            mRemoteUserInfo = remoteUserInfo;
            mIsTrusted = trusted;
            mControllerBinder = controllerBinder;
            mPendingCommands = new ArrayMap<>();
            mRequestedCommandSeqNumbers = new ArraySet<>();
        }

        /**
         * @return remote user info of the controller.
         */
        @NonNull
        public RemoteUserInfo getRemoteUserInfo() {
            return mRemoteUserInfo;
        }

        /**
         * @return package name of the controller.
         */
        @NonNull
        public String getPackageName() {
            return mRemoteUserInfo.getPackageName();
        }

        /**
         * @return uid of the controller. Can be a negative value if the uid cannot be obtained.
         */
        public int getUid() {
            return mRemoteUserInfo.getUid();
        }

        /**
         * Return if the controller has granted {@code android.permission.MEDIA_CONTENT_CONTROL} or
         * has a enabled notification listener so can be trusted to accept connection and incoming
         * command request.
         *
         * @return {@code true} if the controller is trusted.
         * @hide
         */
        public boolean isTrusted() {
            return mIsTrusted;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mControllerBinder, mRemoteUserInfo);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof ControllerInfo)) return false;
            if (this == obj) return true;

            ControllerInfo other = (ControllerInfo) obj;
            if (mControllerBinder != null || other.mControllerBinder != null) {
                return Objects.equals(mControllerBinder, other.mControllerBinder);
            }
            return mRemoteUserInfo.equals(other.mRemoteUserInfo);
        }

        @Override
        public String toString() {
            return "ControllerInfo {pkg=" + mRemoteUserInfo.getPackageName() + ", uid="
                    + mRemoteUserInfo.getUid() + ", allowedCommands=" + mAllowedCommands + "})";
        }

        void notifyConnected(Bundle connectionResult) {
            if (mControllerBinder == null) return;

            try {
                mControllerBinder.notifyConnected(getNextSeqNumber(), connectionResult);
            } catch (RuntimeException e) {
                // Controller may be died prematurely.
            }
        }

        void notifyDisconnected() {
            if (mControllerBinder == null) return;

            try {
                mControllerBinder.notifyDisconnected(getNextSeqNumber());
            } catch (RuntimeException e) {
                // Controller may be died prematurely.
            }
        }

        void sendSessionCommand(Session2Command command, Bundle args,
                ResultReceiver resultReceiver) {
            if (mControllerBinder == null) return;

            try {
                int seq = getNextSeqNumber();
                synchronized (mLock) {
                    mPendingCommands.put(resultReceiver, seq);
                }
                mControllerBinder.sendSessionCommand(seq, command, args, resultReceiver);
            } catch (RuntimeException e) {
                // Controller may be died prematurely.
                synchronized (mLock) {
                    mPendingCommands.remove(resultReceiver);
                }
                resultReceiver.send(RESULT_ERROR_UNKNOWN_ERROR, null);
            }
        }

        void cancelSessionCommand(@NonNull Object token) {
            if (mControllerBinder == null) return;
            Integer seq;
            synchronized (mLock) {
                seq = mPendingCommands.remove(token);
            }
            if (seq != null) {
                mControllerBinder.cancelSessionCommand(seq);
            }
        }

        void receiveCommandResult(ResultReceiver resultReceiver) {
            synchronized (mLock) {
                mPendingCommands.remove(resultReceiver);
            }
        }

        void addRequestedCommandSeqNumber(int seq) {
            synchronized (mLock) {
                mRequestedCommandSeqNumbers.add(seq);
            }
        }

        boolean removeRequestedCommandSeqNumber(int seq) {
            synchronized (mLock) {
                return mRequestedCommandSeqNumbers.remove(seq);
            }
        }

        private int getNextSeqNumber() {
            synchronized (mLock) {
                return mNextSeqNumber++;
            }
        }
    }

    /**
     * Callback to be called for all incoming commands from {@link MediaController2}s.
     * <p>
     * This API is not generally intended for third party application developers.
     */
    public abstract static class SessionCallback {
        /**
         * Called when a controller is created for this session. Return allowed commands for
         * controller. By default it returns {@code null}.
         * <p>
         * You can reject the connection by returning {@code null}. In that case, controller
         * receives {@link MediaController2.ControllerCallback#onDisconnected(MediaController2)}
         * and cannot be used.
         *
         * @param session the session for this event
         * @param controller controller information.
         * @return allowed commands. Can be {@code null} to reject connection.
         */
        @Nullable
        public Session2CommandGroup onConnect(@NonNull MediaSession2 session,
                @NonNull ControllerInfo controller) {
            return null;
        }

        /**
         * Called when a controller is disconnected
         *
         * @param session the session for this event
         * @param controller controller information
         */
        public void onDisconnected(@NonNull MediaSession2 session,
                @NonNull ControllerInfo controller) {}

        /**
         * Called when a controller sent a session command.
         *
         * @param session the session for this event
         * @param controller controller information
         * @param command the session command
         * @param args optional arguments
         * @return the result for the session command. A runtime exception will be thrown if null
         *         is returned.
         */
        @NonNull
        public Session2Command.Result onSessionCommand(@NonNull MediaSession2 session,
                @NonNull ControllerInfo controller, @NonNull Session2Command command,
                @Nullable Bundle args) {
            return null;
        }

        /**
         * Called when the command sent to the controller is finished.
         *
         * @param session the session for this event
         * @param controller controller information
         * @param token the token got from {@link MediaSession2#sendSessionCommand}
         * @param command the session command
         * @param result the result of the session command
         */
        public void onCommandResult(@NonNull MediaSession2 session,
                @NonNull ControllerInfo controller, @NonNull Object token,
                @NonNull Session2Command command, @NonNull Session2Command.Result result) {}
    }
}
