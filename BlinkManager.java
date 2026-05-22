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

    private static final String TAG = "BlinkManager"; //tag used in logging

    //these constants match exactly the blink types published by the rasberrypi
    private static final String BLINK_SINGLE = "single";
    private static final String BLINK_DOUBLE = "double";
    private static final String BLINK_LONG = "long";

    //here is our singleton reference. volatile non-access modifier used to maintain the same instance across all threads
    //what it does is that it ensures each thread reads it from memory rather than reading it from its local cache
    //cache is different for each thread
    private static volatile BlinkManager instance;

    //WeakReference used to allow garbage collector remove the instances which allows us to manage memory and prevent thread from occupying too much
    //active window/activity is used to fetch all the focusable elements to traverse them through single blinks and select them through long blinks
    //activity is essential for intents back to home activity
    private WeakReference<Activity> currentActivityRef;
    private WeakReference<Window> activeWindowRef;

    //storing a global ArrayList to prevent making a new one every blink which uses up memory
    private final ArrayList<View> reusableFocusables = new ArrayList<>();

    //defining the thread that will maintain the rabbitmq connection
    private Thread subscribeThread;

    //defining rabbitmq connection and channel
    private Connection connection;
    private Channel channel;

    //defining the factory used to establish the connection that handles all the parameters for us for easier and portable code
    private final ConnectionFactory factory = new ConnectionFactory();

    //defining the mainThreadHandler to pass all changes made in the thread successfuly to the UI thread
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());

    //private constructor for singletone method that establishes connection and starts the consumer channel
    private BlinkManager() {
        setupConnectionFactory();
        startConsumer();
    }

    //getInstance method that creates a new BlinkManager() if none exists or gets the current one
    public static BlinkManager getInstance() {
        if (instance == null) {
            synchronized (BlinkManager.class) {
                if (instance == null) {
                    instance = new BlinkManager();
                }
            }
        }
        return instance;
    }


    //to register an activity upon starting/resuming it to listen to blinks
    public void registerForegroundActivity(Activity activity) {
        this.currentActivityRef = new WeakReference<>(activity);

        // automatically set the active window to the Activity's window
        this.activeWindowRef = new WeakReference<>(activity.getWindow());
        Log.d(TAG, "Registered Activity and its Window.");
        advanceFocus(activity, null); //to advance focus to first element and reduce number of blinks needed

    }

    // to register a window, used only to register an alert dialog
    public void setDialogWindow(Window dialogWindow) {
        this.activeWindowRef = new WeakReference<>(dialogWindow);
        Log.d(TAG, "Dialog window took focus.");
        advanceFocus(null, null);//to advance focus to first element and reduce number of blinks needed
    }

    //once alert dialog closes we call this to return focus to the calling activity (home activity)
    public void restoreActivityWindow() {
        if (currentActivityRef != null && currentActivityRef.get() != null) {
            this.activeWindowRef = new WeakReference<>(currentActivityRef.get().getWindow());
            Log.d(TAG, "Activity window restored.");
        }
    }

    //used in the onPause methods so the activity stops listening to blinks when we exit it
    public void unregisterForegroundActivity(Activity activity) {
        if (currentActivityRef != null && currentActivityRef.get() == activity) {
            currentActivityRef.clear();
            Log.d(TAG, "Unregistered foreground activity.");
        }
    }


    //method used to start a channel that is listening to blink_exchange on blink_key queue
    private void startConsumer() {
        subscribeThread = new Thread(() -> {
            try {
                connection = factory.newConnection(); //establish rabbitmq connection by asking factory to do it for us (factory handles parameters for us)
                channel = connection.createChannel(); //create a new channel with middleware
                channel.basicQos(1); //wait until we fully consume one blink before sending another one
                AMQP.Queue.DeclareOk q = channel.queueDeclare(); //create a new queue
                channel.queueBind(q.getQueue(), "blink_exchange", "blink_key"); //bind channel queue to blink_exchange

                //define what we will do when we consume
                DefaultConsumer consumer = new DefaultConsumer(channel) {
                    @Override
                    public void handleDelivery(String consumerTag, Envelope envelope,
                                               AMQP.BasicProperties properties, byte[] body) {

                        //retrieve body of message received from middleware (body contains blink type)
                        String message = new String(body, StandardCharsets.UTF_8).trim().toLowerCase();
                        Log.d(TAG, "[r] Received: " + message);

                        //handle the blink action and post changes to the UI thread
                        mainThreadHandler.post(() -> handleBlinkAction(message));
                    }
                };
                //connect the queue to the consuming action
                channel.basicConsume(q.getQueue(), true, consumer);

            } catch (Exception e) {
                Log.e(TAG, "Connection broken: " + e);
            }
        });
        //start the consuming BlinkManager thread
        subscribeThread.start();
    }


    //method that receives the message body, extracts the blink type, and acts accordingly
    private void handleBlinkAction(String rawMessage) {
        //get the current registered activity in case we want to start a new intent (long blink)
        Activity activity = currentActivityRef != null ? currentActivityRef.get() : null;
        if (activity == null || activity.isFinishing()) {
            Log.d(TAG, "No active foreground activity to handle blink.");
            return; //stop handling if we have no current activity listening
        }

        //get the current registered activity window
        Window currentWindow = activeWindowRef != null ? activeWindowRef.get() : null;
        if (currentWindow == null) {
            currentWindow = activity.getWindow(); // Fallback to activity window
        }

        // get the element that currently has the focus so we can either perform action or advance focus
        View currentFocus = currentWindow.getCurrentFocus();


        //perform the blink action
        try {
            JSONObject jsonMessage = new JSONObject(rawMessage); //convert bit stream raw message into readable json object
            String blinkType = jsonMessage.getString("type").toLowerCase(); //extract the blink type from json

            Log.d(TAG, "Successfully parsed blink type: " + blinkType);

            //act accordingly
            switch (blinkType) {
                case BLINK_SINGLE:
                    advanceFocus(activity, currentFocus); //single blinks mean just move forward
                    break;
                case BLINK_DOUBLE:
                    if (currentFocus != null) {
                        Log.d(TAG, "Clicking view: " + currentFocus.getClass().getSimpleName());
                        currentFocus.performClick(); //method of View class to select
                        //double blink means select the current focused view (if a view is focused)
                    } else {
                        Log.w(TAG, "Double blink received, but nothing is currently focused!");
                    }
                    break;
                case BLINK_LONG: //long blink means go back to main menu/home
                    Intent intent = new Intent(activity, Home.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK); //task and activity stack handling
                    activity.startActivity(intent);
                    break;
                default:
                    Log.w(TAG, "Unknown blink type received: " + blinkType);
                    break;
            }

        } catch (JSONException e) { //error in case message received has no "type" key
            Log.e(TAG, "Failed to parse JSON from RabbitMQ. Raw message: " + rawMessage);
        }
    }

    @SuppressLint("WrongConstant") //for some reason it doesn't like .focusSearch(View.FOCUS_FORWARD) but it runs fine
    private void advanceFocus(Activity activity, View currentFocus) {
        //first get the current window to extract all the focusable elements
        Window currentWindow = activeWindowRef != null ? activeWindowRef.get() : null;

        if (currentWindow == null && activity != null) {
            currentWindow = activity.getWindow();
        }

        if (currentWindow == null) return; //we are not currently in any window, exit function

        //get the rootView from which we extract focusables
        View rootView = currentWindow.getDecorView().getRootView();

        //we clear the old focusable, so we can add new ones. important in case we changed activities
        // or our layout is dynamic and we added more elements to the layout upon certain conditions
        reusableFocusables.clear();

        //extract focusables from rootView and add them to our arraylist reusableFocusables
        rootView.addFocusables(reusableFocusables, View.FOCUS_FORWARD);

        if (currentFocus == null) {
            if (!reusableFocusables.isEmpty()) {
                reusableFocusables.get(0).requestFocus();
            }
            return;  //our rootView has no focusable layouts in it or it has only one and we have focused on it
        }

        //identifies the next view to be focused so we can advance focus
        View nextView = currentFocus.focusSearch(View.FOCUS_FORWARD);

        if (nextView != null && nextView != currentFocus) {
            nextView.requestFocus(); //if next view is not null and is a new view we focus on it
        } else {
            //if the next view is the same as the old one or is null that means we have finished the focusable views
            // and we loop back to the beginning to element 0 in reusableFocusables
            if (!reusableFocusables.isEmpty()) {
                reusableFocusables.get(0).requestFocus();
            }
        }
    }

    //code to set up connection factory
    //this code does not start the connection but it prepares it so we can start it easily
    private void setupConnectionFactory() {
        //this uri has the username and password in it
        String uri = "amqps://cdkvkwcj:V03kPdsKHQQ1zGpQZfYExWbK_XAvmH-D@capybara.lmq.cloudamqp.com/cdkvkwcj";
        try {
            factory.setAutomaticRecoveryEnabled(true); //automatically reconnects if connection stops
            factory.setUri(uri); //set the uri to which the factory connects
            //factory handles all the connection parameters for us
        } catch (KeyManagementException | NoSuchAlgorithmException | URISyntaxException e) {
            e.printStackTrace();
        }
    }
}
