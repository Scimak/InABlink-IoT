# InABlink-IoT
Here is the code used in the IoT side of the In a Blink system. It includes the code running on the RasberryPi to operate the camera, the code running on the esp32 to control the lights, and the code in the mobile app to communicate with the RasberryPi and the esp32 modules.

There are 4 code files, 2 of which run on the mobile application, one runs on the rasberrypi, and one runs on the esp32.

## Code running on RasberryPi and ESP32

### RasberryPi code file: rasberrypi_camera_blink_detector.py
this file runs on the rasberrypi, connects to the rabbitmq middleware, activates the camera, detects blinks, and publishes the blinks to the middleware. 4 types of blinks are detected by this code: single blinks, double blinks, triple blinks, and long blinks.

The blink detection occurs by converting the captured frame into grayscale, identifying darker pixels from lighter pixels depending on a threshold value, and identifying the presence of a pupil (eye is opened) based on the number of darker pixels.

converting to grayscale: gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)

counting number of dark pixels based on thresh value: dark_count = cv2.countNonZero(thresh)

identifying if the pupil is present or not: is_now_closed = dark_count <= CLOSED_EYE_PIXEL_LIMIT 

Logic used to distinguish single, double, triple, and long blinks, depends on the time the eye remains closed and the time between blinks. eye_closed variable tracks the eye condition from the previous frame. if they eye was not closed previously and is closed now, current time is recorded. if they eye was closed previously and is open now, the elapsed time is calculated. these values allow us to identify long blinks. If the duration is too short for a long blink, a blink counter increments to detect how many blinks occur in succession to identify double and triple blinks. If the eye was open in the previous frame, the code identifies the time since the last blink. if that time exceeds 400 miliseconds, a message is published to the middleware with the blink count (if the blink count is zero, no message is published).


### esp32 code file: lgihting_changer.py



## Code running in the mobile application

### Mobile middleware receiver code: BlinkManager.java
This file connects to RabbitMQ middleware and acts as the consumer for blink messages. To connect to the middleware successfully, the amqp-client-5.30.0.jar (https://www.rabbitmq.com/client-libraries/java-client) library must be added under the app folder.

This file uses the Singleton pattern to listen to the exchange "blink_exchange" on a separate, re-used thread. To safely update the screen, it uses a Handler attached to the main thread's Looper. The thread uses a ConnectionFactory object which sets up a connection using the URL of the middleware. The connection is set up once the first instance of the BlinkManager is made, and the consumer code starts running.

The consumer code connects to "blink_exchange", receives all blink messages, and handles them by parsing them to json format, identifying the blink type received, and performing the appropriate action depending on the blink type.

The class comes with registration methods to register and unregister activities as they get displayed and removed from the mobile screen. Registration methods for windows are used to register and unregister dialogs. When blinks are detected, the BlinkManager class dynamically extracts all currently focusable elements, and actions are taken on those elements. A single blink advances the focus to the next element, a double blink selects the current focused element, and a long blink starts a new intent back to the home activity.

### Mobile LightingActivity code to talk to ESP32: LightingActivity_code_snippet.java
This file represents a code snippet that runs inside hte LightingActivity.java in the mobile application. The code runs when a lighting selections. The lighting selection is stored in the selected variable, and depeding on which selections is made, the path taken on the ESP32 server is different to change the lgiht output. Volley library is used to make the HTTP request to the ESP32, and the ESP32 takes that request and performs it.
