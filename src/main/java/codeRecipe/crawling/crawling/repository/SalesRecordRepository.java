package codeRecipe.crawling.crawling.repository;

import codeRecipe.crawling.crawling.domain.Product;
import codeRecipe.crawling.crawling.domain.SalesLocation;
import codeRecipe.crawling.crawling.domain.SalesRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface SalesRecordRepository extends JpaRepository<SalesRecord, Long> {
    SalesRecord findTopBySalesLocationAndProductAndSalesDate(
            SalesLocation salesLocation,
            Product product,
            LocalDate salesDate
    );
}
