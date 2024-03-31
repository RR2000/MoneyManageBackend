package com.rondinella.moneymanageapi.banktransactions;

import lombok.SneakyThrows;
import org.mapstruct.Mapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;

@Mapper
public interface BankTransactionMapper {
  BankTransactionMapper INSTANCE = new BankTransactionMapperImpl(); // Singleton instance

  BankTransactionDto toDto(BankTransaction entity);

  BankTransaction toEntity(BankTransactionDto entity);

  List<BankTransactionDto> toDto(List<BankTransaction> entity);

  List<BankTransaction> toEntity(List<BankTransactionDto> entity);

  default BankTransactionDto toDtoFromRevolut(Map<String, Object> rowData) {
    if (rowData == null) {
      return null;
    }
    BankTransactionDto dto = new BankTransactionDto();
    dto.setAccount("Revolut_" + rowData.get("Product"));
    dto.setDatetime(Timestamp.valueOf((String) rowData.get("Started Date")));
    dto.setDescription((String) rowData.get("Description"));
    dto.setAmount(new BigDecimal((String) rowData.get("Amount")));
    dto.setFee(new BigDecimal((String) rowData.get("Fee")));
    dto.setCurrency((String) rowData.get("Currency"));
    return dto;
  }

  @SneakyThrows
  default BankTransactionDto toDtoFromDegiro(Map<String, Object> rowData) {
    BankTransactionDto dto = new BankTransactionDto();

    try {
      dto.setAccount("Degiro");

      String datetimeStr = rowData.get("Data Valore") + " " + rowData.get("Ora");
      SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy HH:mm");
      java.util.Date parsedDate = dateFormat.parse(datetimeStr); // This may throw ParseException
      dto.setDatetime(new Timestamp(parsedDate.getTime()));

      dto.setDescription((String) rowData.get("Descrizione"));
      dto.setAmount(new BigDecimal((String) rowData.get("8")));
      dto.setFee(BigDecimal.ZERO);
      dto.setCurrency("EUR");
    }catch (Exception e){
      throw new RuntimeException();
    }

    return dto;
  }
}
