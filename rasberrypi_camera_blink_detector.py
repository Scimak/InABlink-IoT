from picamera2 import Picamera2
import cv2
import time
import pika
import time
import json
import sys
import os
from utils import get_connection



print("establishing middleware connection...")

try:
    connection = get_connection()
    channel = connection.channel()
except Exception as e:
    print("Unable to connect")
    exit(1)


print("Initializing modern Pi camera. Please wait...")
try:
    picam2 = Picamera2()
    config = picam2.create_preview_configuration(main={"size": (640, 480)})
    picam2.configure(config)
    picam2.start()

    channel.exchange_declare(exchange="blink_exchange", exchange_type="direct")
    
    time.sleep(2.0)
    print("Running! Press 'q' to quit.")

    # to track blinking frame by frame
    eye_closed = False
    closed_start_time = 0
    last_blink_end_time = 0
    blink_count = 0
    

    LONG_BLINK_THRESHOLD = 0.8  # if closed >= 800ms, long blink
    MULTI_BLINK_GAP = 0.4       # 400 ms between blinks to count as multiple
    
    # threshold variables
    DARKNESS_RATIO = 0.50 #pupil pixels must be 50% darker than surroundings
    CLOSED_EYE_PIXEL_LIMIT = 150 #max number of dark pixels that exist but still the eye is considered close
    
    #simple messge displayed to user on camera
    final_msg = "READY"
    msg_color = (255, 255, 255)

    #iterate until user presses q
    while True:
        #process frame by frame, each frame is considered an array of pixels
        frame = picam2.capture_array()

        #convert the full frame to grayscale to perform the calculations (and return array)
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)

        # ROI definition in pixel dimensions (Region Of Interest)
        y1, y2, x1, x2 = 140, 340, 220, 420
        roi = gray[y1:y2, x1:x2]    #taking a slice of the array equivalent to the ROI
        
        #Dynamic threshold changes

        #ROI is blurred to reduce noise
        blurred_roi = cv2.GaussianBlur(roi, (7, 7), 0)

        # calculate the mean brightness of the ROI frame
        mean_brightness = cv2.mean(blurred_roi)[0]
        
        #adjust dynamic threshold to the mean brightness and the pre-set darkness ratio (0.5)
        #so whenever we find sufficient pixels (more than CLOSED_EYE_PIXEL_LIMIT = 150) whose brightness is < 0.5 * mean brightness --> pupil is present --> eye is open
        dynamic_thresh_val = int(mean_brightness * DARKNESS_RATIO)


        #threshold method applies the dynamic threshold to the ROI and returns an array 
        #pixels that meet the threshold: (brightness is < 0.5 * mean brightness) are dark and are assigned the value 255 (becasue of the INV)
        #pixels that do not meet the threshold are assigned the value 0
        _, thresh = cv2.threshold(blurred_roi, dynamic_thresh_val, 255, cv2.THRESH_BINARY_INV)
        dark_count = cv2.countNonZero(thresh) #count the numebr of pixels whos value is >0 (are dark)

        #recorded to identify the elapsed time between blinks/duration of blink to identify multiple blinks/long blink
        current_time = time.time()
        
        # if the number of dark pixels <= CLOSED_EYE_PIXEL_LIMIT, the number of dark pixles is too low so the pupil is not considered present so the eye is closed
        is_now_closed = dark_count <= CLOSED_EYE_PIXEL_LIMIT 

        #dictionary to send to middleware after populating with data
        blinks = {}

        # logic to set timers and detect blinks and identify long blinks
        if is_now_closed and not eye_closed: #eye_closed tracks the eye from the previous frame
            #eye just now closed this frame
            eye_closed = True
            closed_start_time = current_time # basically start a timer
            
        elif not is_now_closed and eye_closed:
            #eye was closed in previous blink, but now just opened
            eye_closed = False
            duration = current_time - closed_start_time # calculate the elapsed time to determine if blink was long
            
            if duration >= LONG_BLINK_THRESHOLD: #if duration >= 800ms
                final_msg = "LONG BLINK" #to output to user
                msg_color = (0, 165, 255) # orange
                blink_count = 0 # reset multi-blink on a long blink
                blinks['type'] = 'long' #prepare to send to middleware
                blinks["timestamp"] = time.time()
                message = json.dumps(blinks) #to convert to bit stream format
                channel.basic_publish(exchange="blink_exchange",
                             routing_key="blink_key", 
                             body=message)#blink published to routing key blink_key
                print("published " + blinks["type"] + " blink") #print to console to keep track
            else:
                blink_count += 1 #eye just opened but duration wasn't enough for long blink, so just record an extra blink
                last_blink_end_time = current_time

        #logic to determine blink types (other than long blinks) based on the tracking variables
        if not eye_closed and blink_count > 0: #eye is open but we have some blinks recorded
            if (current_time - last_blink_end_time) > MULTI_BLINK_GAP: #time after previous blink is too long for more blinks in multi-blinks
                if blink_count == 1: #sinle blink
                    final_msg = "SINGLE BLINK"#display to user
                    msg_color = (0, 255, 0)
                    blinks['type'] = 'single' #prepare middleware data
                    blinks["timestamp"] = time.time()
                    message = json.dumps(blinks)
                    channel.basic_publish(exchange="blink_exchange",
                             routing_key="blink_key", 
                             body=message) #publish to middleware
                    print("published " + blinks["type"] + " blink") #log message on console
                elif blink_count == 2: #double blink
                    final_msg = "DOUBLE BLINK"
                    msg_color = (255, 255, 0)
                    blinks['type'] = 'double'
                    blinks["timestamp"] = time.time()
                    message = json.dumps(blinks)
                    channel.basic_publish(exchange="blink_exchange",
                             routing_key="blink_key", 
                             body=message)
                    print("published " + blinks["type"] + " blink")
                elif blink_count >= 3: #triple blink
                    final_msg = "TRIPLE BLINK"
                    msg_color = (255, 0, 255)
                    blinks['type'] = 'triple'
                    blinks["timestamp"] = time.time()
                    message = json.dumps(blinks)
                    channel.basic_publish(exchange="blink_exchange",
                             routing_key="blink_key", 
                             body=message)
                    print("published " + blinks["type"] + " blink")
                
                blink_count = 0 # reset since now we published and blink sequence is over
        

        # Display to he user on the camera interface
        # eye status (Top left)
        status = "CLOSED" if eye_closed else "OPEN"
        cv2.putText(frame, f"Status: {status}", (10, 40), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (255, 255, 255), 2)
        
        #display dynamic threshold value for debugging
        cv2.putText(frame, f"Thresh: {dynamic_thresh_val} | Pixels: {dark_count}", (10, 70), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (200, 200, 200), 1)
        
        # blink result status (Top right)
        cv2.putText(frame, final_msg, (350, 40), cv2.FONT_HERSHEY_SIMPLEX, 0.8, msg_color, 2)
        
        cv2.rectangle(frame, (x1, y1), (x2, y2), msg_color, 2)
        cv2.imshow("Blink Detector", frame)
        cv2.imshow("Threshold", thresh)

        if cv2.waitKey(1) & 0xFF == ord('q'):
            break

except Exception as e:
    print(f"\nERROR: {e}")
finally:
    print("Shutting down...")
    try: picam2.stop()
    except: pass
    cv2.destroyAllWindows()
    connection.close()
