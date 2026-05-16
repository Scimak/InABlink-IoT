package com.example.inablink;

import static androidx.core.content.ContextCompat.getDrawable;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;


import android.content.Intent;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.*;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.inablink.databinding.ActivityMedicinePage2Binding;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Random;


import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import org.json.JSONObject;
import org.json.JSONException;

public class BlinkManager {

    private static final String TAG = "BlinkManager";
    private static final String BLINK_SINGLE = "single";
    private static final String BLINK_DOUBLE = "double";
    private static final String BLINK_LONG = "long";

    private static volatile BlinkManager instance;

    // We only need to hold a WeakReference to the Activity now
    private WeakReference<Activity> currentActivityRef;

    private WeakReference<Window> activeWindowRef;

    private Thread subscribeThread;
    private Connection connection;
    private Channel channel;
    private final ConnectionFactory factory = new ConnectionFactory();
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());

    private BlinkManager() {
        setupConnectionFactory();
        startConsumer();
    }

    public static BlinkManager getInstance() {
        System.out.println("getting instance");
        if (instance == null) {
            synchronized (BlinkManager.class) {
                if (instance == null) {
                    instance = new BlinkManager();
                }
            }
        }
        return instance;
    }

    // Notice we no longer need to pass a View!
    public void registerForegroundActivity(Activity activity) {
        this.currentActivityRef = new WeakReference<>(activity);

        // Automatically set the active window to the Activity's window
        this.activeWindowRef = new WeakReference<>(activity.getWindow());
        Log.d(TAG, "Registered Activity and its Window.");
    }

    // 2. A specific method JUST for Dialogs to temporarily steal focus
    public void setDialogWindow(Window dialogWindow) {
        this.activeWindowRef = new WeakReference<>(dialogWindow);
        Log.d(TAG, "Dialog window took focus.");
    }

    // 3. A method to give focus back to the Activity when the Dialog closes
    public void restoreActivityWindow() {
        if (currentActivityRef != null && currentActivityRef.get() != null) {
            this.activeWindowRef = new WeakReference<>(currentActivityRef.get().getWindow());
            Log.d(TAG, "Activity window restored.");
        }
    }

    public void unregisterForegroundActivity(Activity activity) {
        if (currentActivityRef != null && currentActivityRef.get() == activity) {
            currentActivityRef.clear();
            Log.d(TAG, "Unregistered foreground activity.");
        }
    }

    private void startConsumer() {
        subscribeThread = new Thread(() -> {
            try {
                connection = factory.newConnection();
                channel = connection.createChannel();
                channel.basicQos(1);
                AMQP.Queue.DeclareOk q = channel.queueDeclare();
                channel.queueBind(q.getQueue(), "blink_exchange", "blink_key");

                DefaultConsumer consumer = new DefaultConsumer(channel) {
                    @Override
                    public void handleDelivery(String consumerTag, Envelope envelope,
                                               AMQP.BasicProperties properties, byte[] body) {

                        String message = new String(body, StandardCharsets.UTF_8).trim().toLowerCase();
                        Log.d(TAG, "[r] Received: " + message);

                        mainThreadHandler.post(() -> handleBlinkAction(message));
                    }
                };
                channel.basicConsume(q.getQueue(), true, consumer);

            } catch (Exception e) {
                Log.e(TAG, "Connection broken: " + e);
            }
        });
        subscribeThread.start();
    }

// Add this import at the top of your file

// ... inside BlinkManager.java ...

    private void handleBlinkAction(String rawMessage) {
        Activity activity = currentActivityRef != null ? currentActivityRef.get() : null;

        if (activity == null || activity.isFinishing()) {
            Log.d(TAG, "No active foreground activity to handle blink.");
            return;
        }

        try {
            // 1. Convert the raw RabbitMQ string into a JSON Object
            JSONObject jsonMessage = new JSONObject(rawMessage);

            // 2. Extract exactly the "type" value and convert it to lowercase
            String blinkType = jsonMessage.getString("type").toLowerCase();

            Log.d(TAG, "Successfully parsed blink type: " + blinkType);

            View currentFocus = activity.getCurrentFocus();

            // 3. Run our standard switch statement on the extracted type
            switch (blinkType) {
                case BLINK_SINGLE:
                    advanceFocus(activity, currentFocus);
                    break;
                case BLINK_DOUBLE:
                    if (currentFocus != null) currentFocus.performClick();
                    break;
                case BLINK_LONG:
                    Intent intent = new Intent(activity, Home.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(intent);
                    break;
                default:
                    Log.w(TAG, "Unknown blink type received: " + blinkType);
                    break;
            }

        } catch (JSONException e) {
            // If the message isn't valid JSON, or doesn't contain "type", it will fail safely here
            Log.e(TAG, "Failed to parse JSON from RabbitMQ. Raw message: " + rawMessage);
        }
    }
//    @SuppressLint("WrongConstant")
//    private void advanceFocus(Activity activity, View currentFocus) {
//        if (currentFocus != null) {
//            View nextView = currentFocus.focusSearch(View.FOCUS_FORWARD);
//
//            if (nextView != null && nextView != currentFocus) {
//                nextView.requestFocus();
//                return;
//            }
//        }
//
//        // Dynamically grab the Root View of the screen directly from the Activity's Window
//        View rootView = activity.getWindow().getDecorView().getRootView();
//        ArrayList<View> focusables = rootView.getFocusables(View.FOCUS_FORWARD);
//
//        if (focusables != null && !focusables.isEmpty()) {
//            focusables.get(0).requestFocus();
//        }
//    }

    private void advanceFocus(Activity activity, View currentFocus) {
        Window currentWindow = activeWindowRef != null ? activeWindowRef.get() : null;

        // Fallback to the Activity's window if no active window is set
        if (currentWindow == null && activity != null) {
            currentWindow = activity.getWindow();
        }

        if (currentWindow == null) return;

        // Grab the root view of WHICHEVER window is currently active
        View rootView = currentWindow.getDecorView().getRootView();
        ArrayList<View> focusables = rootView.getFocusables(View.FOCUS_FORWARD);

        if (focusables == null || focusables.isEmpty()) {
            return;
        }

        if (currentFocus == null) {
            focusables.get(0).requestFocus();
        } else {
            int currentIndex = focusables.indexOf(currentFocus);
            int nextIndex = (currentIndex + 1) % focusables.size();
            focusables.get(nextIndex).requestFocus();
        }
    }

    private void setupConnectionFactory() {
        String uri = "amqps://cdkvkwcj:V03kPdsKHQQ1zGpQZfYExWbK_XAvmH-D@capybara.lmq.cloudamqp.com/cdkvkwcj";
        try {
            factory.setAutomaticRecoveryEnabled(true);
            factory.setUri(uri);
        } catch (KeyManagementException | NoSuchAlgorithmException | URISyntaxException e) {
            e.printStackTrace();
        }
    }
}