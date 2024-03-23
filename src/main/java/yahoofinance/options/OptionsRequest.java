package yahoofinance.options;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yahoofinance.Utils;
import yahoofinance.YahooFinance;
import yahoofinance.options.dao.MarketData;
import yahoofinance.options.dao.OptionContract;
import yahoofinance.util.RedirectableRequest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class OptionsRequest {
    private static final Logger log = LoggerFactory.getLogger(OptionsRequest.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private String underlying;
    public OptionsRequest(String underlying)
    {
        this.underlying=underlying;
    }


    private String getJson(Long expirationDate) throws IOException {

        Map<String, String> params = new LinkedHashMap<String, String>();
        if (expirationDate != null)
            params.put("date",""+expirationDate);
        String url = YahooFinance.OPTIONS_BASE_URL + URLEncoder.encode(this.underlying , "UTF-8") + "?" + Utils.getURLParameters(params);

        // Get CSV from Yahoo
        log.info("Sending request: " + url);

        URL request = new URL(url);
        RedirectableRequest redirectableRequest = new RedirectableRequest(request, 5);
        redirectableRequest.setConnectTimeout(YahooFinance.CONNECTION_TIMEOUT);
        redirectableRequest.setReadTimeout(YahooFinance.CONNECTION_TIMEOUT);
        URLConnection connection = redirectableRequest.openConnection();

        InputStreamReader is = new InputStreamReader(connection.getInputStream());
        BufferedReader br = new BufferedReader(is);
        StringBuilder builder = new StringBuilder();
        for (String line = br.readLine(); line != null; line = br.readLine()) {
            if (builder.length() > 0) {
                builder.append("\n");
            }
            builder.append(line);
        }
        return builder.toString();
    }

    private Long convertToEpoche(LocalDate expirationDate) {
        return expirationDate.getLong(ChronoField.EPOCH_DAY);
    }

    public Map<LocalDate,Map<OptionContract.OptionType, List<OptionContract>>> getResult() throws IOException {
        String json = getJson(null);
        JsonNode expirationNodes = objectMapper.readTree(json).get("optionChain").get("result").get(0).get("expirationDates");

        Map<LocalDate,Map<OptionContract.OptionType, List<OptionContract>>> ret = new TreeMap<>();

        for (int i=0; i < expirationNodes.size() ; i++)
        {
            json=getJson(expirationNodes.get(i).asLong());
            JsonNode resultNode = objectMapper.readTree(json).get("optionChain").get("result").get(0).get("options").get(0);

            JsonNode resultNodeCalls = resultNode.get("calls");
            JsonNode resultNodePuts = resultNode.get("puts");

            List<OptionContract> calls = parse(OptionContract.OptionType.Call, resultNodeCalls);
            List<OptionContract> puts = parse(OptionContract.OptionType.Put, resultNodePuts);

            Map<OptionContract.OptionType, List<OptionContract>> optionsPerExpiration = new HashMap<>();
            optionsPerExpiration.put(OptionContract.OptionType.Call, calls);
            optionsPerExpiration.put(OptionContract.OptionType.Put, puts);

            ret.put(convertExpirationDate(expirationNodes.get(i).asLong()),optionsPerExpiration);
        }

        return ret;
    }

    private List<OptionContract> parse(OptionContract.OptionType type, JsonNode resultNodeCalls)
    {
        List<OptionContract> contracts = new ArrayList<>(resultNodeCalls.size());
        for (JsonNode contract : resultNodeCalls)
        {
            MarketData marketData = new MarketData();
            {
                JsonNode lastPriceNode = contract.get("lastPrice");
                JsonNode volumeNode = contract.get("volume");
                JsonNode openInterestNode = contract.get("openInterest");
                JsonNode bidNode = contract.get("bid");
                JsonNode askNode = contract.get("ask");
                JsonNode lastTradeDateNode = contract.get("lastTradeDate");
                JsonNode impliedVolatilityNode = contract.get("impliedVolatility");

                marketData.setLastPrice(BigDecimal.valueOf(lastPriceNode.asDouble()));
                if (volumeNode!=null) marketData.setVolume(volumeNode.asInt());
                if (openInterestNode!=null) marketData.setOpenInterest(openInterestNode.asInt());
                if (bidNode!=null) marketData.setBid(BigDecimal.valueOf(bidNode.asDouble()));
                if (askNode!=null) marketData.setAsk(BigDecimal.valueOf(askNode.asDouble()));
                if (lastTradeDateNode!=null) marketData.setLastTradeDate(convertDate(lastTradeDateNode.asLong()));
                if (impliedVolatilityNode!=null) marketData.setImpliedVol(impliedVolatilityNode.asDouble());
            }

            JsonNode contractSymbolNode =contract.get("contractSymbol");
            JsonNode strikeNode = contract.get("strike");
            JsonNode currencyNode = contract.get("currency");
            JsonNode contractSizeNode = contract.get("contractSize");
            JsonNode expirationNode = contract.get("expiration");

            OptionContract oc = new OptionContract();
            oc.setSymbol(contractSymbolNode.asText().toUpperCase());
            oc.setOptionType(type);
            if (currencyNode != null)  oc.setCurrency(currencyNode.asText().toUpperCase());
            oc.setStrike(BigDecimal.valueOf(strikeNode.asDouble()));
            oc.setExpiration(convertExpirationDate(expirationNode.asLong()));
            oc.setContractSize(contractSizeNode.asText());
            oc.setMarketData(marketData);

            contracts.add(oc);
        }

        return contracts;
    }

    private LocalDate convertExpirationDate(long asLong) {

        return Instant.ofEpochMilli(asLong*1000).atZone(ZoneId.systemDefault()).toLocalDate().plus(1, ChronoUnit.DAYS);
    }

    private Date convertDate(long asLong) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(asLong * 1000);
        return calendar.getTime();
    }

    public static void main(String[] args) throws Exception
    {
        OptionsRequest optionsRequest = new OptionsRequest("VTV");
        Map<LocalDate,Map<OptionContract.OptionType, List<OptionContract>>>  request = optionsRequest.getResult();
        System.out.println(request);
    }
}
