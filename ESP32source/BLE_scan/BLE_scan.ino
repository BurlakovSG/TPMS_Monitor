#include <ArduinoJson.h>

#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEScan.h>
#include <BLEAdvertisedDevice.h>
#include <BLEAddress.h>

#include "WiFi.h"
#include "SPIFFS.h"
#include "ESPAsyncWebServer.h"

const char Page_WaitAndReload[] PROGMEM = R"=====(
<meta http-equiv="refresh" content="10; URL=/wifi_settings.html">
Please Wait....Configuring and Restarting.
)=====";

const char Page_Restart[] PROGMEM = R"=====(
<meta http-equiv="refresh" content="10; URL=/general.html">
Please Wait....Configuring and Restarting.
)=====";

typedef struct {
  String ssid;
  String password;
  bool dhcp;
  IPAddress ip;
  IPAddress netmask;
  IPAddress gateway;
  IPAddress dns;
} wifiConfig_t;

typedef struct {
  String tpms_id;
  float pressure;
  float temperature;
  float bat_voltage;
} tpms_data_t;

wifiConfig_t wifiConfig;

tpms_data_t top_left;
tpms_data_t top_right;
tpms_data_t rear_left;
tpms_data_t rear_right;
unsigned long timeout = 0;

//unsigned long mill;
//unsigned long mill2;

String ssid;
String password;

int scanTime = 1; //5 In seconds
BLEScan* pBLEScan;

AsyncWebServer server(80);
AsyncWebSocket wsTPMS("/ws_tpms");

AsyncWebSocketClient * globalClient = NULL;

/*********************************************************************************
 * BEGIN BLE SECTION
 ********************************************************************************/
class MyAdvertisedDeviceCallbacks: public BLEAdvertisedDeviceCallbacks {
    void onResult(BLEAdvertisedDevice advertisedDevice) {
//      Serial.println(advertisedDevice.toString().c_str());         

      if (advertisedDevice.haveName() && advertisedDevice.haveManufacturerData() &&
          (advertisedDevice.getName().substr(0, 4) == "TPMS"))
      {
        std::string md = advertisedDevice.getManufacturerData();
        unsigned long p = 0;

        for (int i = 11; i >= 8; i--) {
          p = p << 8;
          p += md[i];
        }

        float pres = p / 100000.0;

        long t = 0;

        for (int i = 15; i >= 12; i--) {
          t = t << 8;
          t += md[i];
        }

        float temp = t / 100.0;

        int bat = md[16];
        float v = 0;

        if ((bat >= 0) && (bat <= 4))
        {
          v = (((((bat - 0 << 16) / 4  * 224 >> 16) + 1136) / 2) / 1023.0 ) * 3.6;
        }
        else if ((5 <= bat) && (bat <= 28))
        {
          v = (((((bat - 4 << 16) / 24  * 224 >> 16) + 1360) / 2) / 1023.0 ) * 3.6;
        }
        else if ((29 <= bat) && (bat <= 100))
        {
          v = (((((bat - 28 << 16) / 72  * 121 >> 16) + 1584) / 2) / 1023.0 ) * 3.6;
        }

        String TPMS_name = advertisedDevice.getName().c_str();
        String place = "";

        Serial.printf("%s - Pressure: %.2f, Temp: %.2f, Bat: %.2f\n", TPMS_name, pres, temp, v);

        if (TPMS_name.endsWith(top_left.tpms_id)) {
          place = "top_left";
          top_left.pressure = pres;
          top_left.temperature = temp;
          top_left.bat_voltage = v;
        }
        else if (TPMS_name.endsWith(top_right.tpms_id)) {
          place = "top_right";
          top_right.pressure = pres;
          top_right.temperature = temp;
          top_right.bat_voltage = v;
        }
        else if (TPMS_name.endsWith(rear_left.tpms_id)) {
          place = "rear_left";
          rear_left.pressure = pres;
          rear_left.temperature = temp;
          rear_left.bat_voltage = v;
        }
        else if (TPMS_name.endsWith(rear_right.tpms_id)) {
          place = "rear_right";
          rear_right.pressure = pres;
          rear_right.temperature = temp;
          rear_right.bat_voltage = v;
        }
  
        if (globalClient != NULL && globalClient->status() == WS_CONNECTED) {
          String json = "{";
          json += "\"type\": \"TPMS\",";
          json += "\"data\": {";
          json += "\"ID\":\"" + TPMS_name.substring(6) + "\"";
          json += ",\"place\":\"" + place + "\"";
          json += ",\"pressure\":" + String(pres);
          json += ",\"temperature\":" + String(temp);
          json += ",\"bat_voltage\":" + String(v);
          json += "}}";        
          globalClient->text(json);
        }
      }
    }
};

boolean loadTPMSsettings() {
  if (!SPIFFS.exists("/tpms_settings.json")) {
    Serial.println("File tpms_settings.json do not exists!");
    return false;    
  }
  
  File file = SPIFFS.open("/tpms_settings.json", "r");
  if (!file) {
    Serial.println("Failed to open TPMS config file!");
    return false;
  }

  size_t size = file.size();
  if (size > 1024) {
    Serial.println("TPMS config file size is too large!");
    return false;
  }

  std::unique_ptr<char[]> buf(new char[size]);
  file.readBytes(buf.get(), size);
  file.close();
  Serial.println(buf.get());

  DynamicJsonBuffer jsonBuffer(1024);
  JsonObject& json = jsonBuffer.parseObject(buf.get());

  if (!json.success()) {
    Serial.println("Failed to parse TPMS config file");
    return false;
  }

  top_left.tpms_id = json["top_left"].as<const char *>();
  top_left.pressure = 0;
  top_left.temperature = 0;
  top_left.bat_voltage = 0;
  
  top_right.tpms_id = json["top_right"].as<const char *>();
  top_right.pressure = 0;
  top_right.temperature = 0;
  top_right.bat_voltage = 0;
  
  rear_left.tpms_id = json["rear_left"].as<const char *>();
  rear_left.pressure = 0;
  rear_left.temperature = 0;
  rear_left.bat_voltage = 0;
  
  rear_right.tpms_id = json["rear_right"].as<const char *>();
  rear_right.pressure = 0;
  rear_right.temperature = 0;
  rear_right.bat_voltage = 0;
  
  timeout = json["timeout"].as<long>();
  
  return true;
}

boolean saveTPMSsettings() {
  DynamicJsonBuffer jsonBuffer(1024);

  JsonObject& json = jsonBuffer.createObject();
  json["top_left"] = top_left.tpms_id;
  json["top_right"] = top_right.tpms_id;
  json["rear_left"] = rear_left.tpms_id;
  json["rear_right"] = rear_right.tpms_id;
  json["timeout"] = timeout;

  File file = SPIFFS.open("/tpms_settings.json", "w");
  if (!file) {
    Serial.println("Failed to open TPMS config file!");
    file.close();
    return false;
  }

  json.printTo(file);
  file.flush();
  file.close();
  return true;
}

void initBLEscan() {
  if (!loadTPMSsettings()) {
    return;
  }
  
  BLEDevice::init("");
  pBLEScan = BLEDevice::getScan(); //create new scan
  pBLEScan->setAdvertisedDeviceCallbacks(new MyAdvertisedDeviceCallbacks());
  pBLEScan->setActiveScan(true); //active scan uses more power, but get results faster
  pBLEScan->setInterval(100); //100
  pBLEScan->setWindow(99);  // 99 less or equal setInterval value
}
/*********************************************************************************
 * END BLE SECTION
 ********************************************************************************/

void onWsTpmsEvent(AsyncWebSocket * server, AsyncWebSocketClient * client, AwsEventType type, void * arg, uint8_t *data, size_t len) {

  if (type == WS_EVT_CONNECT) {

    Serial.println("Websocket client connection received");
    globalClient = client;

  } else if (type == WS_EVT_DISCONNECT) {

    Serial.println("Websocket client connection finished");
    globalClient = NULL;

  }
}

String getContentType(String filename, AsyncWebServerRequest *request) {
  if (request->hasArg("download")) return "application/octet-stream";
  else if (filename.endsWith(".htm")) return "text/html";
  else if (filename.endsWith(".html")) return "text/html";
  else if (filename.endsWith(".css")) return "text/css";
  else if (filename.endsWith(".js")) return "application/javascript";
  else if (filename.endsWith(".json")) return "application/json";
  else if (filename.endsWith(".png")) return "image/png";
  else if (filename.endsWith(".gif")) return "image/gif";
  else if (filename.endsWith(".jpg")) return "image/jpeg";
  else if (filename.endsWith(".ico")) return "image/x-icon";
  else if (filename.endsWith(".xml")) return "text/xml";
  else if (filename.endsWith(".pdf")) return "application/x-pdf";
  else if (filename.endsWith(".zip")) return "application/x-zip";
  else if (filename.endsWith(".gz")) return "application/x-gzip";
  return "text/plain";
}

bool handleFileRead(String path, AsyncWebServerRequest *request) {
  if (path.endsWith("/"))
      path += "index.htm";
  
  String contentType = getContentType(path, request);
  String pathWithGz = path + ".gz";
  
  if (SPIFFS.exists(pathWithGz) || SPIFFS.exists(path)) {
    if (SPIFFS.exists(pathWithGz)) {
      path += ".gz";
  }
        
  AsyncWebServerResponse *response = request->beginResponse(SPIFFS, path, contentType);
  if (path.endsWith(".gz"))
    response->addHeader("Content-Encoding", "gzip");
    request->send(response);   
    return true;
  } else {
    return false;
  }
}

void notFound(AsyncWebServerRequest *request) {
  AsyncWebServerResponse *response = request->beginResponse(200);
  response->addHeader("Connection", "close");
  response->addHeader("Access-Control-Allow-Origin", "*");
  if (!handleFileRead(request->url(), request))
      request->send(404, "text/plain", "FileNotFound");
  delete response;;
}

boolean checkRange(String Value) {
    if (Value.toInt() < 0 || Value.toInt() > 255) {
        return false;
    } else {
        return true;
    }
} 

unsigned char h2int(char c) {
    if (c >= '0' && c <= '9') {
        return((unsigned char)c - '0');
    }
    if (c >= 'a' && c <= 'f') {
        return((unsigned char)c - 'a' + 10);
    }
    if (c >= 'A' && c <= 'F') {
        return((unsigned char)c - 'A' + 10);
    }
    return(0);
}

String urldecode(String input) {
    char c;
    String ret = "";

    for (byte t = 0; t < input.length(); t++) {
        c = input[t];
        if (c == '+') c = ' ';
        if (c == '%') {


            t++;
            c = input[t];
            t++;
            c = (h2int(c) << 4) | h2int(input[t]);
        }

        ret.concat(c);
    }
    return ret;
}

/*********************************************************************************
 * BEGIN WI-FI SECTION
 ********************************************************************************/
boolean loadWifiConfig() {
  if (!SPIFFS.exists("/wifi_settings.json")) {
    Serial.println("File wifi_settings.json do not exists!");
    return false;    
  }
  
  File file = SPIFFS.open("/wifi_settings.json", "r");
  if (!file) {
    Serial.println("Failed to open Wi-Fi config file!");
    return false;
  }

  size_t size = file.size();
  if (size > 1024) {
    Serial.println("Wi-Fi config file size is too large!");
    return false;
  }

  std::unique_ptr<char[]> buf(new char[size]);
  file.readBytes(buf.get(), size);
  file.close();
  Serial.println(buf.get());

  DynamicJsonBuffer jsonBuffer(1024);
  JsonObject& json = jsonBuffer.parseObject(buf.get());

  if (!json.success()) {
    Serial.println("Failed to parse Wi-Fi config file");
    return false;
  }

  wifiConfig.ssid = json["ssid"].as<const char *>();
  wifiConfig.password = json["password"].as<const char *>();
  wifiConfig.dhcp = json["dhcp"].as<bool>();
  wifiConfig.ip = IPAddress(json["ip"][0], json["ip"][1], json["ip"][2], json["ip"][3]);
  wifiConfig.netmask = IPAddress(json["netmask"][0], json["netmask"][1], json["netmask"][2], json["netmask"][3]);
  wifiConfig.gateway = IPAddress(json["gateway"][0], json["gateway"][1], json["gateway"][2], json["gateway"][3]);
  wifiConfig.dns = IPAddress(json["dns"][0], json["dns"][1], json["dns"][2], json["dns"][3]);

  return true;
}

boolean saveWifiConfig() {
  DynamicJsonBuffer jsonBuffer(1024);

  JsonObject& json = jsonBuffer.createObject();
  json["ssid"] = wifiConfig.ssid;
  json["password"] = wifiConfig.password;
  json["dhcp"] = wifiConfig.dhcp;

  JsonArray& jsonip = json.createNestedArray("ip");
  jsonip.add(wifiConfig.ip[0]);
  jsonip.add(wifiConfig.ip[1]);
  jsonip.add(wifiConfig.ip[2]);
  jsonip.add(wifiConfig.ip[3]);

  JsonArray& jsonNM = json.createNestedArray("netmask");
  jsonNM.add(wifiConfig.netmask[0]);
  jsonNM.add(wifiConfig.netmask[1]);
  jsonNM.add(wifiConfig.netmask[2]);
  jsonNM.add(wifiConfig.netmask[3]);

  JsonArray& jsonGateway = json.createNestedArray("gateway");
  jsonGateway.add(wifiConfig.gateway[0]);
  jsonGateway.add(wifiConfig.gateway[1]);
  jsonGateway.add(wifiConfig.gateway[2]);
  jsonGateway.add(wifiConfig.gateway[3]);

  JsonArray& jsondns = json.createNestedArray("dns");
  jsondns.add(wifiConfig.dns[0]);
  jsondns.add(wifiConfig.dns[1]);
  jsondns.add(wifiConfig.dns[2]);
  jsondns.add(wifiConfig.dns[3]);

  File file = SPIFFS.open("/wifi_settings.json", "w");
  if (!file) {
    Serial.println("Failed to open Wi-Fi config file!");
    file.close();
    return false;
  }

  json.printTo(file);
  file.flush();
  file.close();
  return true;
}

void configureWifi() {
  Serial.println();
  Serial.print("Wi-Fi SSID: ");
  Serial.println(wifiConfig.ssid);
  Serial.print("Wi-Fi password: ");
  Serial.println(wifiConfig.password);

  WiFi.disconnect(); 
  WiFi.mode(WIFI_STA);
  WiFi.begin(wifiConfig.ssid.c_str(), wifiConfig.password.c_str());

  if (!wifiConfig.dhcp) {
    WiFi.config(wifiConfig.ip, wifiConfig.gateway, wifiConfig.netmask, wifiConfig.dns);
  }

  Serial.print("Connecting to WiFi");

  while (WiFi.waitForConnectResult() != WL_CONNECTED) {
    delay(3000);
    Serial.print(".");
    WiFi.begin(wifiConfig.ssid.c_str(), wifiConfig.password.c_str());
    WiFi.reconnect();    
  }

  Serial.println();
  Serial.print("Connected with IP: ");
  Serial.println(WiFi.localIP());  
}

void configureWifiAP() {
  WiFi.mode(WIFI_AP);
  String APname = "ESP32_BLEscan";
  WiFi.softAP(APname.c_str());
}
/*********************************************************************************
 * END WI-FI SECTION
 ********************************************************************************/

void setup() {
  Serial.begin(115200);

  if (!SPIFFS.begin()) {
    Serial.println("An Error has occurred while mounting SPIFFS");
    return;
  }

  initBLEscan();

  if (loadWifiConfig()) {
    configureWifi();
  }
  else {
    configureWifiAP();
  }

  wsTPMS.onEvent(onWsTpmsEvent);
  server.addHandler(&wsTPMS);

  server.on("/admin/scan", HTTP_GET, [](AsyncWebServerRequest * request) {
    String json = "[";
    int n = WiFi.scanComplete();
    if (n == WIFI_SCAN_FAILED) {
        WiFi.scanNetworks(true);
    } else if (n) {
        for (int i = 0; i < n; ++i) {
            if (i) json += ",";
            json += "{";
            json += "\"rssi\":" + String(WiFi.RSSI(i));
            json += ",\"ssid\":\"" + WiFi.SSID(i) + "\"";
            json += ",\"bssid\":\"" + WiFi.BSSIDstr(i) + "\"";
            json += ",\"channel\":" + String(WiFi.channel(i));
            json += ",\"secure\":" + String(WiFi.encryptionType(i));
            json += ",\"hidden\":" + String("false");
            json += "}";
        }
        WiFi.scanDelete();
        if (WiFi.scanComplete() == WIFI_SCAN_FAILED) {
            WiFi.scanNetworks(true);
        }
    }
    json += "]";
    request->send(200, "text/json", json);
    json = "";
  });

  server.on("/admin/connectionstate", HTTP_GET, [](AsyncWebServerRequest * request) {
    String state = "N/A";
    
    if (WiFi.status() == 0) state = "Idle";
    else if (WiFi.status() == 1) state = "NO SSID AVAILBLE";
    else if (WiFi.status() == 2) state = "SCAN COMPLETED";
    else if (WiFi.status() == 3) state = "CONNECTED";
    else if (WiFi.status() == 4) state = "CONNECT FAILED";
    else if (WiFi.status() == 5) state = "CONNECTION LOST";
    else if (WiFi.status() == 6) state = "DISCONNECTED";

    WiFi.scanNetworks(true);

    String values = "";
    values += "connectionstate|" + state + "|div\n";
    request->send(200, "text/plain", values);
    state = "";
    values = "";
  });

  server.on("/admin/values", HTTP_GET, [](AsyncWebServerRequest * request) {
    String values = "";

    values += "ssid|" + (String)wifiConfig.ssid + "|input\n";
    values += "password|" + (String)wifiConfig.password + "|input\n";
    values += "dhcp|" + (String)(wifiConfig.dhcp ? "checked" : "") + "|chk\n";
    values += "ip_0|" + (String)wifiConfig.ip[0] + "|input\n";
    values += "ip_1|" + (String)wifiConfig.ip[1] + "|input\n";
    values += "ip_2|" + (String)wifiConfig.ip[2] + "|input\n";
    values += "ip_3|" + (String)wifiConfig.ip[3] + "|input\n";
    values += "nm_0|" + (String)wifiConfig.netmask[0] + "|input\n";
    values += "nm_1|" + (String)wifiConfig.netmask[1] + "|input\n";
    values += "nm_2|" + (String)wifiConfig.netmask[2] + "|input\n";
    values += "nm_3|" + (String)wifiConfig.netmask[3] + "|input\n";
    values += "gw_0|" + (String)wifiConfig.gateway[0] + "|input\n";
    values += "gw_1|" + (String)wifiConfig.gateway[1] + "|input\n";
    values += "gw_2|" + (String)wifiConfig.gateway[2] + "|input\n";
    values += "gw_3|" + (String)wifiConfig.gateway[3] + "|input\n";
    values += "dns_0|" + (String)wifiConfig.dns[0] + "|input\n";
    values += "dns_1|" + (String)wifiConfig.dns[1] + "|input\n";
    values += "dns_2|" + (String)wifiConfig.dns[2] + "|input\n";
    values += "dns_3|" + (String)wifiConfig.dns[3] + "|input\n";

    request->send(200, "text/plain", values);
    values = "";
  });

  server.on("/admin/tpms_settings", HTTP_GET, [](AsyncWebServerRequest * request) {
    String values = "";

    values += "top_left|" + (String)top_left.tpms_id + "|input\n";
    values += "top_right|" + (String)top_right.tpms_id + "|input\n";
    values += "rear_left|" + (String)rear_left.tpms_id + "|input\n";
    values += "rear_right|" + (String)rear_right.tpms_id + "|input\n";
    values += "timeout|" + (String)timeout + "|input\n";

    request->send(200, "text/plain", values);
    values = "";
  });  

  server.on("/admin/tpms_values", HTTP_GET, [](AsyncWebServerRequest * request) {
    String values = "";

    values += "top_left_id|" + (String)top_left.tpms_id + "|input\n";
    values += "top_left_pressure|" + (String)top_left.pressure + "|input\n";
    values += "top_left_temperature|" + (String)top_left.temperature + "|input\n";
    values += "top_left_bat_voltage|" + (String)top_left.bat_voltage + "|input\n";
    values += "top_right_id|" + (String)top_right.tpms_id + "|input\n";
    values += "top_right_pressure|" + (String)top_right.pressure + "|input\n";
    values += "top_right_temperature|" + (String)top_right.temperature + "|input\n";
    values += "top_right_bat_voltage|" + (String)top_right.bat_voltage + "|input\n";
    values += "rear_left_id|" + (String)rear_left.tpms_id + "|input\n";
    values += "rear_left_pressure|" + (String)rear_left.pressure + "|input\n";
    values += "rear_left_temperature|" + (String)rear_left.temperature + "|input\n";
    values += "rear_left_bat_voltage|" + (String)rear_left.bat_voltage + "|input\n";
    values += "rear_right_id|" + (String)rear_right.tpms_id + "|input\n";
    values += "rear_right_pressure|" + (String)rear_right.pressure + "|input\n";
    values += "rear_right_temperature|" + (String)rear_right.temperature + "|input\n";
    values += "rear_right_bat_voltage|" + (String)rear_right.bat_voltage + "|input\n";

    request->send(200, "text/plain", values);
    values = "";
  });

server.on("/tpms_settings.html", HTTP_GET, [](AsyncWebServerRequest * request) {
    if (request->args() > 0) {
      if (request->hasArg("top_left")) {
        top_left.tpms_id = urldecode(request->arg("top_left"));
      }
      
      if (request->hasArg("top_right")) {
        top_right.tpms_id = urldecode(request->arg("top_right"));
      }
      
      if (request->hasArg("rear_left")) {
        rear_left.tpms_id = urldecode(request->arg("rear_left"));
      }
      
      if (request->hasArg("rear_right")) {
        rear_right.tpms_id = urldecode(request->arg("rear_right"));
      }

      if (request->hasArg("timeout")) {
        timeout = request->arg("timeout").toInt();
      }

      if (!saveTPMSsettings()) {
        Serial.println("TPMS config don't save!");
      }
      
      request->send_P(200, "text/html", Page_WaitAndReload);
      
    }
    else {
      request->send(SPIFFS, "/tpms_settings.html", "text/html");
    }
  });  

  server.on("/index.html", HTTP_GET, [](AsyncWebServerRequest * request) {
    if (request->args() > 0) {
      if (request->hasArg("ssid")) {
        wifiConfig.ssid = urldecode(request->arg("ssid"));
      }

      if (request->hasArg("password")) {
        wifiConfig.password = urldecode(request->arg("password"));
      }

      wifiConfig.dhcp = request->hasArg("dhcp");

      if (request->hasArg("ip_0") && 
          request->hasArg("ip_1") &&
          request->hasArg("ip_2") && 
          request->hasArg("ip_3")) {

        if (checkRange(request->arg("ip_0")) && 
            checkRange(request->arg("ip_1")) &&
            checkRange(request->arg("ip_2")) && 
            checkRange(request->arg("ip_3"))) {
              
          wifiConfig.ip[0] = request->arg("ip_0").toInt();
          wifiConfig.ip[1] = request->arg("ip_1").toInt();
          wifiConfig.ip[2] = request->arg("ip_2").toInt();
          wifiConfig.ip[3] = request->arg("ip_3").toInt();
        }
      }

      if (request->hasArg("nm_0") && 
          request->hasArg("nm_1") &&
          request->hasArg("nm_2") && 
          request->hasArg("nm_3")) {

        if (checkRange(request->arg("nm_0")) && 
            checkRange(request->arg("nm_1")) &&
            checkRange(request->arg("nm_2")) && 
            checkRange(request->arg("nm_3"))) {
              
          wifiConfig.netmask[0] = request->arg("nm_0").toInt();
          wifiConfig.netmask[1] = request->arg("nm_1").toInt();
          wifiConfig.netmask[2] = request->arg("nm_2").toInt();
          wifiConfig.netmask[3] = request->arg("nm_3").toInt();
        }
      }

      if (request->hasArg("gw_0") && 
          request->hasArg("gw_1") &&
          request->hasArg("gw_2") && 
          request->hasArg("gw_3")) {

        if (checkRange(request->arg("gw_0")) && 
            checkRange(request->arg("gw_1")) &&
            checkRange(request->arg("gw_2")) && 
            checkRange(request->arg("gw_3"))) {
              
          wifiConfig.gateway[0] = request->arg("gw_0").toInt();
          wifiConfig.gateway[1] = request->arg("gw_1").toInt();
          wifiConfig.gateway[2] = request->arg("gw_2").toInt();
          wifiConfig.gateway[3] = request->arg("gw_3").toInt();
        }
      }
      
      if (request->hasArg("dns_0") && 
          request->hasArg("dns_1") &&
          request->hasArg("dns_2") && 
          request->hasArg("dns_3")) {

        if (checkRange(request->arg("dns_0")) && 
            checkRange(request->arg("dns_1")) &&
            checkRange(request->arg("dns_2")) && 
            checkRange(request->arg("dns_3"))) {
              
          wifiConfig.dns[0] = request->arg("dns_0").toInt();
          wifiConfig.dns[1] = request->arg("dns_1").toInt();
          wifiConfig.dns[2] = request->arg("dns_2").toInt();
          wifiConfig.dns[3] = request->arg("dns_3").toInt();
        }
      }

      request->send_P(200, "text/html", Page_WaitAndReload);
      
      if (!saveWifiConfig()) {
        Serial.println("WiFi config don't save!");
      }

      delay(1000);
      SPIFFS.end();
      ESP.restart();
    }
    else {
      request->send(SPIFFS, "/index.html", "text/html");
    }
  });

  server.on("/test", HTTP_GET, [](AsyncWebServerRequest * request) {
    int paramsNr = request->params();
    Serial.println(paramsNr);

    for (int i = 0; i < paramsNr; i++) {
      AsyncWebParameter* p = request->getParam(i);
      Serial.print("Param name: ");
      Serial.println(p->name());
      Serial.print("Param value: ");
      Serial.println(p->value());
      Serial.println("------");
    }

    if (request->hasArg("dhcp")) {
      String arg = request->arg("dhcp");
      Serial.println(arg);
    }

    request->send(200, "text/plain", "message received");
  });

  server.onNotFound(notFound);

  server.begin();
}

void loop() {
  BLEScanResults foundDevices = pBLEScan->start(scanTime, false);
  pBLEScan->clearResults();   // delete results fromBLEScan buffer to release memory

//  unsigned long curr = millis();
//
//  if (mill == 0)
//    mill = millis();
//
//  if (mill2 == 0)
//    mill2 = millis();
    
//
//  if (globalClient != NULL && 
//      globalClient->status() == WS_CONNECTED &&
//      ((curr - mill) > 1000 || mill == 0)) {
//          String json = "{";
//          json += "\"type\": \"TPMS\",";
//          json += "\"data\": {";
//          json += "\"ID\":\"100506\"";
//          json += ",\"place\":\"top_left\"";
//          json += ",\"pressure\":" + String(random(150, 300) / 100.0);
//          json += ",\"temperature\":" + String(random(1000, 3000) / 100.0);
//          json += ",\"bat_voltage\":" + String(random(200, 300) / 100.0);
//          json += "}}";        
//          globalClient->text(json);
//
//          Serial.println(json);
//
//          mill = millis();
//  }

//  if (globalClient != NULL && 
//    globalClient->status() == WS_CONNECTED &&
//    (curr - mill2) > 2000) {
//      String json = "{";
//      json += "\"type\": \"TPMS\",";
//      json += "\"data\": {";
//      json += "\"ID\":\"100506\"";
//      json += ",\"place\":\"top_left\"";
//
//      if ((curr - mill) > 1000 && (curr - mill) < 3000)
//        json += ",\"pressure\":2.32";
//      else if ((curr - mill) > 3000 && (curr - mill) < 5000)
//        json += ",\"pressure\":2.15";  
//      else if ((curr - mill) > 5000 && (curr - mill) < 7000)
//        json += ",\"pressure\":2.37";
//      else if ((curr - mill) > 7000) {
//        json += ",\"pressure\":2.42";
//        mill = millis();
//      }
//      
//      json += ",\"temperature\":25.64";
//      json += ",\"bat_voltage\":2.89";
//      json += "}}";        
//      globalClient->text(json);
//
//      Serial.println(json);
//      mill2 = millis();
//  }
}
