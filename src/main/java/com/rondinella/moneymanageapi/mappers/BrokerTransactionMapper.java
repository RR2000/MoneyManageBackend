package com.rondinella.moneymanageapi.mappers;

import com.rondinella.moneymanageapi.dtos.BrokerTransactionDto;
import com.rondinella.moneymanageapi.enitities.BrokerTransaction;
import lombok.SneakyThrows;
import org.mapstruct.Mapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;

@Mapper
public interface BrokerTransactionMapper {
  BrokerTransactionMapper INSTANCE = new BrokerTransactionMapperImpl(); // Singleton instance

  List<BrokerTransactionDto> toDtoFromDegiro(List<Map<String, String>> degiroMap);

  @SneakyThrows
  default BrokerTransactionDto toDtoFromDegiro(Map<String, String> degiroMap) {
    if (degiroMap == null) {
      return null;
    }
    BrokerTransactionDto brokerTransactionDto = new BrokerTransactionDto();

    String datetimeStr = degiroMap.get("Data") + " " + degiroMap.get("Ora");
    SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy HH:mm");
    java.util.Date parsedDate = dateFormat.parse(datetimeStr); // This may throw ParseException
    brokerTransactionDto.setDatetime(new Timestamp(parsedDate.getTime()));
    brokerTransactionDto.setDescription(degiroMap.get("Prodotto"));
    brokerTransactionDto.setIsin(degiroMap.get("ISIN"));
    brokerTransactionDto.setQuantity(new BigDecimal(degiroMap.get("Quantità").replace(",", ".")));
    brokerTransactionDto.setStockPrice(new BigDecimal(degiroMap.get("Quotazione").replace(",", ".")));

    if (!degiroMap.get("Commissioni di").isEmpty())
      brokerTransactionDto.setFee(new BigDecimal(
          degiroMap.get("Commissioni di").replace(",", "."))
          .multiply(BigDecimal.valueOf(-1)));
    else
      brokerTransactionDto.setFee(BigDecimal.ZERO);

    return brokerTransactionDto;
  }

  Iterable<BrokerTransaction> toEntity(List<BrokerTransactionDto> brokerTransactionDtos);
}
