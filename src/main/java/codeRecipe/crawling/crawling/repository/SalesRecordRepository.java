package codeRecipe.crawling.crawling.repository;

import codeRecipe.crawling.crawling.domain.Product;
import codeRecipe.crawling.crawling.domain.SalesLocation;
import codeRecipe.crawling.crawling.domain.SalesRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface SalesRecordRepository extends JpaRepository<SalesRecord, Long> {
    SalesRecord findTopBySalesLocationAndProductAndSalesDate(
            SalesLocation salesLocation,
            Product product,
            LocalDate salesDate
    );

    @Query("SELECT SUM(s.quantity) FROM SalesRecord s WHERE s.salesDate = :salesDate")
    Long getTotalQuantityByDate(@Param("salesDate") LocalDate salesDate);

    @Query("SELECT SUM(s.salesAmount) FROM SalesRecord s WHERE s.salesDate = :salesDate")
    Long getTotalSalesAmountByDate(@Param("salesDate") LocalDate salesDate);

    @Query("SELECT SUM(sr.quantity), SUM(sr.salesAmount) " +
            "FROM SalesRecord sr " +
            "WHERE sr.salesDate = :salesDate")
    Object[] getTotalQuantityAndSalesAmountByDate(@Param("salesDate") LocalDate salesDate);

//    @Query("SELECT p.productName, p.productCode, COUNT(sr.product.productId), SUM(sr.salesAmount) " +
//            "FROM SalesRecord sr " +
//            "JOIN sr.product p " +
//            "WHERE sr.salesDate = :salesDate " +
//            "GROUP BY p.productName, p.productCode"+
//            "ORDER BY COUNT(sr.product.productId) DESC" )
//    List<Object[]> findSalesSummaryByDate(@Param("salesDate") LocalDate salesDate);

    @Query("SELECT p.productName, p.productCode, SUM(sr.quantity), SUM(sr.salesAmount) " +
            "FROM SalesRecord sr " +
            "JOIN sr.product p " +
            "WHERE sr.salesDate = :salesDate " +
            "GROUP BY p.productName, p.productCode " +
            "ORDER BY SUM(sr.quantity) DESC")
    List<Object[]> findSalesSummaryByDate(@Param("salesDate") LocalDate salesDate);

    @Query("SELECT sl.locationName, sl.region, SUM(sr.quantity), SUM(sr.salesAmount) " +
            "FROM SalesRecord sr " +
            "JOIN sr.salesLocation sl " +
            "WHERE sr.salesDate = :salesDate " +
            "GROUP BY sl.locationName, sl.region " +
            "ORDER BY SUM(sr.quantity) DESC")
    List<Object[]> findSalesSummaryByLocationAndDate(@Param("salesDate") LocalDate salesDate);


    SalesRecord findByProductAndSalesDateAndSalesLocation(Product product, LocalDate salesDate, SalesLocation salesLocation);

    @Query("SELECT SUM(s.quantity) FROM SalesRecord s WHERE s.salesDate BETWEEN :startDate AND :endDate")
    Long getTotalQuantityByDateRange(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT SUM(s.salesAmount) FROM SalesRecord s WHERE s.salesDate BETWEEN :startDate AND :endDate")
    Long getTotalSalesAmountByDateRange(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT p.productName, p.productCode, SUM(sr.quantity), SUM(sr.salesAmount) " +
            "FROM SalesRecord sr " +
            "JOIN sr.product p " +
            "WHERE sr.salesDate BETWEEN :startDate AND :endDate " +
            "GROUP BY p.productName, p.productCode " +
            "ORDER BY SUM(sr.quantity) DESC")
    List<Object[]> findSalesSummaryByDateRange(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT sl.locationName, sl.region, SUM(sr.quantity), SUM(sr.salesAmount) " +
            "FROM SalesRecord sr " +
            "JOIN sr.salesLocation sl " +
            "WHERE sr.salesDate BETWEEN :startDate AND :endDate " +
            "GROUP BY sl.locationName, sl.region " +
            "ORDER BY SUM(sr.quantity) DESC")
    List<Object[]> findSalesSummaryByLocationAndDateRange(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

}
