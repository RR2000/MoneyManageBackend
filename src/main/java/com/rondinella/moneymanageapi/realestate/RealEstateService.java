package com.rondinella.moneymanageapi.realestate;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class RealEstateService {

  private final RealEstateRepository realEstateRepository;

  public RealEstateService(RealEstateRepository realEstateRepository) {
    this.realEstateRepository = realEstateRepository;
  }

  public List<RealEstate> findAll() {
    return realEstateRepository.findAll();
  }

  public RealEstate findById(Long id) {
    return realEstateRepository.findById(id)
        .orElseThrow(() -> new NoSuchElementException("RealEstate not found: " + id));
  }

  public RealEstate save(RealEstateDto dto) {
    RealEstate entity = toEntity(dto);
    entity.setId(null);
    return realEstateRepository.save(entity);
  }

  public RealEstate update(Long id, RealEstateDto dto) {
    if (!realEstateRepository.existsById(id)) {
      throw new NoSuchElementException("RealEstate not found: " + id);
    }
    RealEstate entity = toEntity(dto);
    entity.setId(id);
    return realEstateRepository.save(entity);
  }

  public void delete(Long id) {
    realEstateRepository.deleteById(id);
  }

  public BigDecimal totalWorth() {
    return realEstateRepository.findAll().stream()
        .map(r -> r.getCurrentEstimatedValue() != null ? r.getCurrentEstimatedValue() : BigDecimal.ZERO)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private RealEstate toEntity(RealEstateDto dto) {
    return new RealEstate(
        dto.getId(),
        dto.getName(),
        dto.getAddress(),
        dto.getPurchaseDate(),
        dto.getPurchasePrice(),
        dto.getCurrentEstimatedValue(),
        dto.getMonthlyRent(),
        dto.getMonthlyExpenses(),
        dto.getCurrency()
    );
  }
}
