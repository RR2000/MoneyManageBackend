package yahoofinance.histquotes2;

import org.openqa.selenium.By;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.util.Set;

/**
 * Created by Stijn on 23/05/2017.
 */
public class CrumbManager {

  private static final Logger log = LoggerFactory.getLogger(CrumbManager.class);

  private static String crumb = "";
  private static String cookie = "";

  private static void setCookieAndCrumb() throws IOException {
    // Configure Chrome options to disable notifications
    ChromeOptions chromeOptions = new ChromeOptions();
    chromeOptions.addArguments("--disable-notifications");
    chromeOptions.addArguments("--no-sandbox");
    chromeOptions.addArguments("--headless");
    chromeOptions.addArguments("--disable-gpu");
    chromeOptions.addArguments("--disable-dev-shm-usage");
    chromeOptions.addArguments("--remote-debugging-port=9222");
    // Define Selenium WebDriver URL
    String seleniumUrl = "http://selenium:4444/wd/hub";
    // Create a new instance of the ChromeDriver with configured options
    WebDriver driver = new RemoteWebDriver(new URL(seleniumUrl), chromeOptions);

    try {
      // Navigate to Yahoo homepage
      driver.get("https://www.yahoo.com");

      // Accept cookies if the consent dialog is present
      try {
        WebElement acceptCookiesButton = driver.findElement(By.xpath("//button[@class='btn secondary accept-all consent_reject_all_3']"));
        acceptCookiesButton.click();
      } catch (Exception e) {
        System.out.println("No cookie consent dialog found.");
      }

      // Navigate to the URL
      driver.get("https://query1.finance.yahoo.com/v1/test/getcrumb");

      // Get the page source (HTML) and store it in the crumb string
      crumb = driver.findElement(By.tagName("pre")).getText();

      // Get cookies and store them in the cookie string
      Set<Cookie> cookies = driver.manage().getCookies();
      StringBuilder cookieBuilder = new StringBuilder();
      for (org.openqa.selenium.Cookie c : cookies) {
        cookieBuilder.append(c.getName()).append("=").append(c.getValue()).append("; ");
      }
      cookie = cookieBuilder.toString();
    } finally {
      // Close the browser
      driver.quit();
    }
  }

  public static void refresh() throws IOException {
    setCookieAndCrumb();
  }

  public static synchronized String getCrumb() throws IOException {
    if (crumb == null || crumb.isEmpty()) {
      refresh();
    }
    return crumb;
  }

  public static String getCookie() throws IOException {
    if (cookie == null || cookie.isEmpty()) {
      refresh();
    }
    return cookie;
  }

}
