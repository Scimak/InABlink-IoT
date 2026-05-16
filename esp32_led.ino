#include <WiFi.h>
#include <WebServer.h>

// 1. Replace with your network credentials
const char* ssid = "YOUR_WIFI_NAME";
const char* password = "YOUR_WIFI_PASSWORD";

// 2. Define the web server on port 80
WebServer server(80);

// 3. Define the LED pin (GPIO8 is standard for many C3 minis)
const int ledPin = 8; 

void setup() {
  Serial.begin(115200);
  pinMode(ledPin, OUTPUT);

  // --- Wi-Fi Connection Logic ---
  Serial.print("Connecting to ");
  Serial.println(ssid);
  
  WiFi.begin(ssid, password);

  // Wait for connection
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }

  Serial.println("");
  Serial.println("WiFi connected!");
  Serial.print("IP address: ");
  Serial.println(WiFi.localIP()); // COPY THIS TO YOUR ANDROID APP

  // --- Route Definitions ---
  
  // Route for http://[IP]/off
  server.on("/off", []() {
    analogWrite(ledPin, 0);
    server.send(200, "text/plain", "LED is OFF");
    Serial.println("Command: OFF");
  });

  // Route for http://[IP]/dim
  server.on("/dim", []() {
    analogWrite(ledPin, 50); // PWM value (0-255)
    server.send(200, "text/plain", "LED is DIM");
    Serial.println("Command: DIM");
  });

  // Route for http://[IP]/on
  server.on("/on", []() {
    analogWrite(ledPin, 255);
    server.send(200, "text/plain", "LED is ON");
    Serial.println("Command: ON");
  });

  server.begin();
  Serial.println("HTTP server started");
}

void loop() {
  // This keeps the server listening for new requests
  server.handleClient();
}