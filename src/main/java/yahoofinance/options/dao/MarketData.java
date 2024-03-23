package yahoofinance.options.dao;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;

public class MarketData {
    private BigDecimal lastPrice;
    private int volume;
    private int openInterest;
    private BigDecimal bid;
    private BigDecimal ask;
    private Date lastTradeDate;
    private double impliedVol;

    public BigDecimal getLastPrice() {
        return lastPrice;
    }

    public void setLastPrice(BigDecimal lastPrice) {
        this.lastPrice = lastPrice;
    }

    public int getVolume() {
        return volume;
    }

    public void setVolume(int volume) {
        this.volume = volume;
    }

    public int getOpenInterest() {
        return openInterest;
    }

    public void setOpenInterest(int openInterest) {
        this.openInterest = openInterest;
    }

    public BigDecimal getBid() {
        return bid;
    }

    public void setBid(BigDecimal bid) {
        this.bid = bid;
    }

    public BigDecimal getAsk() {
        return ask;
    }

    public void setAsk(BigDecimal ask) {
        this.ask = ask;
    }

    public Date getLastTradeDate() {
        return lastTradeDate;
    }

    public void setLastTradeDate(Date lastTradeDate) {
        this.lastTradeDate = lastTradeDate;
    }

    public double getImpliedVol() {
        return impliedVol;
    }

    public void setImpliedVol(double impliedVol) {
        this.impliedVol = impliedVol;
    }
}
