import com.android.volley.toolbox.Volley;
private static final String PREFS_NAME = "InABlinkPrefs";
private static final String ESP_IP = "http://172.20.10.3"; //ip address is changed based on connection


//code inside calling method setLight
RequestQueue queue = Volley.newRequestQueue(this);
        String url = ESP_IP + path;

        StringRequest stringRequest = new StringRequest(Request.Method.GET, url,
                response -> {
                    Log.d("ESP32", "Response: " + response);
                },
                error -> {
                    Log.e("ESP32", "Error: " + error.toString());
                });

        queue.add(stringRequest);
