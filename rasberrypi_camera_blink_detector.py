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

    # --- Tracking Variables ---
    eye_closed = False
    closed_start_time = 0
    last_blink_end_time = 0
    blink_count = 0
    
    # --- Tuning Thresholds ---
    LONG_BLINK_THRESHOLD = 0.8  # If closed > 0.8s, it's a "Long Blink"
    MULTI_BLINK_GAP = 0.4       # Time to wait for a second/third tap
    
    # --- Vision Thresholds ---
    # Adjust this ratio if it's too sensitive or not sensitive enough.
    # 0.50 means the pupil must be 50% darker than the average skin tone in the ROI.
    DARKNESS_RATIO = 0.50       
    # Maximum dark pixels allowed to consider the eye "closed"
    CLOSED_EYE_PIXEL_LIMIT = 150 
    
    final_msg = "READY"
    msg_color = (255, 255, 255)

    while True:
        frame = picam2.capture_array()
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)

        # ROI Definition
        y1, y2, x1, x2 = 140, 340, 220, 420
        roi = gray[y1:y2, x1:x2]
        
        # 1. Blur the ROI slightly to reduce camera noise (especially in low light)
        blurred_roi = cv2.GaussianBlur(roi, (7, 7), 0)

        # 2. Calculate the average ambient brightness of the ROI
        mean_brightness = cv2.mean(blurred_roi)[0]
        
        # 3. Create a dynamic threshold based on current lighting conditions
        # If the room is bright (mean = 150), threshold becomes 75.
        # If the room is dark (mean = 60), threshold becomes 30.
        dynamic_thresh_val = int(mean_brightness * DARKNESS_RATIO)

        # Apply the adaptive threshold
        _, thresh = cv2.threshold(blurred_roi, dynamic_thresh_val, 255, cv2.THRESH_BINARY_INV)
        dark_count = cv2.countNonZero(thresh)

        current_time = time.time()
        
        # Inverted logic: few dark pixels = eye shut (pupil is hidden)
        is_now_closed = dark_count <= CLOSED_EYE_PIXEL_LIMIT 

        blinks = {}

        # --- Detection Logic ---
        if is_now_closed and not eye_closed:
            # Eye just closed
            eye_closed = True
            closed_start_time = current_time
            
        elif not is_now_closed and eye_closed:
            # Eye just opened
            eye_closed = False
            duration = current_time - closed_start_time
            
            if duration >= LONG_BLINK_THRESHOLD:
                final_msg = "LONG BLINK"
                msg_color = (0, 165, 255) # Orange
                blink_count = 0 # Reset multi-blink on a long blink
                blinks['type'] = 'long'
                blinks["timestamp"] = time.time()
                message = json.dumps(blinks)
                channel.basic_publish(exchange="blink_exchange",
                             routing_key="blink_key", 
                             body=message)
                print("published " + blinks["type"] + " blink")
            else:
                blink_count += 1
                last_blink_end_time = current_time

        # Decision Making
        if not eye_closed and blink_count > 0:
            if (current_time - last_blink_end_time) > MULTI_BLINK_GAP:
                if blink_count == 1:
                    final_msg = "SINGLE BLINK"
                    msg_color = (0, 255, 0)
                    blinks['type'] = 'single'
                    blinks["timestamp"] = time.time()
                    message = json.dumps(blinks)
                    channel.basic_publish(exchange="blink_exchange",
                             routing_key="blink_key", 
                             body=message)
                    print("published " + blinks["type"] + " blink")
                elif blink_count == 2:
                    final_msg = "DOUBLE BLINK"
                    msg_color = (255, 255, 0)
                    blinks['type'] = 'double'
                    blinks["timestamp"] = time.time()
                    message = json.dumps(blinks)
                    channel.basic_publish(exchange="blink_exchange",
                             routing_key="blink_key", 
                             body=message)
                    print("published " + blinks["type"] + " blink")
                elif blink_count >= 3:
                    final_msg = "TRIPLE BLINK"
                    msg_color = (255, 0, 255)
                    blinks['type'] = 'triple'
                    blinks["timestamp"] = time.time()
                    message = json.dumps(blinks)
                    channel.basic_publish(exchange="blink_exchange",
                             routing_key="blink_key", 
                             body=message)
                    print("published " + blinks["type"] + " blink")
                
                blink_count = 0 # Reset for next sequence
        

        # --- Visuals ---
        # Live status (Top left)
        status = "CLOSED" if eye_closed else "OPEN"
        cv2.putText(frame, f"Status: {status}", (10, 40), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (255, 255, 255), 2)
        
        # Display the dynamic threshold value to help you debug/tune
        cv2.putText(frame, f"Thresh: {dynamic_thresh_val} | Pixels: {dark_count}", (10, 70), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (200, 200, 200), 1)
        
        # Result status (Top right)
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
