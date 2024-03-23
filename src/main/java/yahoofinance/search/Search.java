package yahoofinance.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import yahoofinance.YahooFinance;
import yahoofinance.histquotes2.CrumbManager;
import yahoofinance.util.RedirectableRequest;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;

public class Search {
  String query;
  RedirectableRequest redirectableRequest;
  Map<String, String> requestProperties;
  ObjectMapper objectMapper = new ObjectMapper();

  public Search(String query) {
    this.query = query;
    try {
      initialize();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void initialize() throws IOException {
    URL request = new URL(YahooFinance.SEARCH_URL + "?q=" + query);
    redirectableRequest = new RedirectableRequest(request, 5);
    redirectableRequest.setConnectTimeout(YahooFinance.CONNECTION_TIMEOUT);
    redirectableRequest.setReadTimeout(YahooFinance.CONNECTION_TIMEOUT);
    requestProperties = new HashMap<>();
    requestProperties.put("Cookie", CrumbManager.getCookie());
  }

  public String getFirstResult() {
    try {
      URLConnection connection = redirectableRequest.openConnection(requestProperties);
      InputStreamReader is = new InputStreamReader(connection.getInputStream());
      JsonNode node = objectMapper.readTree(is).get("quotes").get(0).get("symbol");

      return node.asText();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
