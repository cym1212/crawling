package codeRecipe.crawling.crawling.repository;

import codeRecipe.crawling.crawling.domain.CoupangSales;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface CoupangSalesRepository extends JpaRepository<CoupangSales, Long> {

    List<CoupangSales> findBySalesDate(LocalDate salesDate);

    List<CoupangSales> findBySalesDateBetween(LocalDate startDate, LocalDate endDate);

    List<CoupangSales> findByVendorItemIdAndSalesDateBetween(Long vendorItemId, LocalDate startDate, LocalDate endDate);
}
