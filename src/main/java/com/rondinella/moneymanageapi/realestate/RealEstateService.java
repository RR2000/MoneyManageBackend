package com.rondinella.moneymanageapi.realestate;

import com.rondinella.moneymanageapi.assets.Checks;
import com.rondinella.moneymanageapi.banktransactions.AccountPages;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Real estate of the user. What is saved follows the rules of the assets page ({@link Checks}): a name, the
 * estimated value, amounts of 0 or more, an ISO currency; address, purchase date and price, rent and expenses optional.
 */
@Service
public class RealEstateService {

  /** per user */
  static final int MAX_ITEMS = 500;

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

  @Transactional
  public RealEstate save(RealEstateDto dto) {
    if (realEstateRepository.count() >= MAX_ITEMS) {
      throw Checks.bad("TOO_MANY");
    }
    RealEstate entity = toEntity(dto);
    entity.setId(null);
    return realEstateRepository.save(entity);
  }

  @Transactional
  public RealEstate update(Long id, RealEstateDto dto) {
    if (!realEstateRepository.existsById(id)) {
      throw new NoSuchElementException("RealEstate not found: " + id);
    }
    RealEstate entity = toEntity(dto);
    entity.setId(id);
    return realEstateRepository.save(entity);
  }

  @Transactional
  public void delete(Long id) {
    realEstateRepository.delete(findById(id));
  }

  private static RealEstate toEntity(RealEstateDto dto) {
    if (dto == null) {
      throw Checks.bad("NAME_REQUIRED");
    }
    LocalDate today = LocalDate.now(AccountPages.ZONE);
    return new RealEstate(
        dto.getId(),
        Checks.name(dto.getName()),
        Checks.text(dto.getAddress()),
        Checks.date(dto.getPurchaseDate(), today),
        Checks.optionalAmount(dto.getPurchasePrice()),
        Checks.amount(dto.getCurrentEstimatedValue()),
        Checks.optionalAmount(dto.getMonthlyRent()),
        Checks.optionalAmount(dto.getMonthlyExpenses()),
        Checks.currency(dto.getCurrency())
    );
  }
}
