# InABlink-IoT
Here is the code used in the IoT side of the In a Blink system. It includes the code running on the RasberryPi to operate the camera, the code running on the esp32 to control the lights, and the code in the mobile app to communicate with the RasberryPi and the esp32 modules.

There are 4 code files, 2 of which run on the mobile application, one runs on the rasberrypi, and one runs on the esp32.

## RasberryPi code file: rasberrypi_camera_blink_detector.py
this file runs on the rasberrypi, connects to the rabbitmq middleware, activates the camera, detects blinks, and publishes the blinks to the middleware. 4 types of blinks are detected by this code: single blinks, double blinks, triple blinks, and long blinks.

The blink detection occurs by converting the captured frame into grayscale, identifying darker pixels from lighter pixels depending on a threshold value, and identifying the presence of a pupil (eye is opened) based on the number of darker pixels.
converting to grayscale: gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
counting number of dark pixels based on thresh value: dark_count = cv2.countNonZero(thresh)
identifying if the pupil is present or not: is_now_closed = dark_count <= CLOSED_EYE_PIXEL_LIMIT 

Logic used to distinguish single, double, triple, and long blinks, depends on the time the eye remains closed and the time between blinks. eye_closed variable tracks the eye condition from the previous frame. if they eye was not closed previously and is closed now, current time is recorded. if they eye was closed previously and is open now, the elapsed time is calculated. these values allow us to identify long blinks. If the duration is too short for a long blink, a blink counter increments to detect how many blinks occur in succession to identify double and triple blinks. If the eye was open in the previous frame, the code identifies the time since the last blink. if that time exceeds 400 miliseconds, a message is published to the middleware with the blink count (if the blink count is zero, no message is published).
