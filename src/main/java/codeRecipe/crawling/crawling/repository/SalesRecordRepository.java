package codeRecipe.crawling.crawling.repository;

import codeRecipe.crawling.crawling.domain.Product;
import codeRecipe.crawling.crawling.domain.SalesLocation;
import codeRecipe.crawling.crawling.domain.SalesRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    // 어드민 사이트용 매출 조회 쿼리들
    @Query("SELECT sr FROM SalesRecord sr " +
            "JOIN FETCH sr.salesLocation sl " +
            "JOIN FETCH sr.product p " +
            "WHERE sr.salesDate BETWEEN :startDate AND :endDate " +
            "ORDER BY sr.salesDate DESC, sl.locationName, p.productName")
    List<SalesRecord> findSalesDataByDateRange(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT sr FROM SalesRecord sr " +
            "JOIN FETCH sr.salesLocation sl " +
            "JOIN FETCH sr.product p " +
            "WHERE sr.salesLocation.locationId = :locationId " +
            "AND sr.salesDate BETWEEN :startDate AND :endDate " +
            "ORDER BY sr.salesDate DESC, p.productName")
    List<SalesRecord> findSalesDataByLocationAndDateRange(@Param("locationId") Long locationId, 
                                                          @Param("startDate") LocalDate startDate, 
                                                          @Param("endDate") LocalDate endDate);

    @Query("SELECT sr.salesDate, SUM(sr.salesAmount), SUM(sr.actualSales), SUM(sr.quantity), COUNT(sr.recordId) " +
            "FROM SalesRecord sr " +
            "WHERE sr.salesDate = :date " +
            "GROUP BY sr.salesDate")
    List<Object[]> findDailySummary(@Param("date") LocalDate date);

    @Query("SELECT EXTRACT(YEAR FROM sr.salesDate), EXTRACT(MONTH FROM sr.salesDate), " +
            "SUM(sr.salesAmount), SUM(sr.actualSales), SUM(sr.quantity), COUNT(sr.recordId), " +
            "COUNT(DISTINCT sr.salesLocation.locationId) " +
            "FROM SalesRecord sr " +
            "WHERE EXTRACT(YEAR FROM sr.salesDate) = :year AND EXTRACT(MONTH FROM sr.salesDate) = :month " +
            "GROUP BY EXTRACT(YEAR FROM sr.salesDate), EXTRACT(MONTH FROM sr.salesDate)")
    List<Object[]> findMonthlySummary(@Param("year") int year, @Param("month") int month);

    @Query("SELECT SUM(sr.salesAmount), SUM(sr.actualSales), SUM(sr.quantity), COUNT(sr.recordId), " +
            "COUNT(DISTINCT sr.salesLocation.locationId), COUNT(DISTINCT sr.product.productId) " +
            "FROM SalesRecord sr " +
            "WHERE sr.salesDate BETWEEN :startDate AND :endDate " +
            "AND (:locationId IS NULL OR sr.salesLocation.locationId = :locationId)")
    List<Object[]> findPeriodSummary(@Param("startDate") LocalDate startDate, 
                                     @Param("endDate") LocalDate endDate, 
                                     @Param("locationId") Long locationId);

    @Query("SELECT sl.locationName, sl.region, SUM(sr.salesAmount), SUM(sr.actualSales), SUM(sr.quantity), COUNT(sr.recordId) " +
            "FROM SalesRecord sr " +
            "JOIN sr.salesLocation sl " +
            "WHERE EXTRACT(YEAR FROM sr.salesDate) = :year AND EXTRACT(MONTH FROM sr.salesDate) = :month " +
            "GROUP BY sl.locationName, sl.region " +
            "ORDER BY SUM(sr.salesAmount) DESC " +
            "LIMIT :limit")
    List<Object[]> findTopLocationsByMonth(@Param("year") int year, @Param("month") int month, @Param("limit") int limit);

    // 페이징을 위한 쿼리들
    @Query("SELECT sr FROM SalesRecord sr " +
            "JOIN FETCH sr.salesLocation sl " +
            "JOIN FETCH sr.product p " +
            "WHERE sr.salesDate BETWEEN :startDate AND :endDate " +
            "ORDER BY sr.salesDate DESC, sl.locationName, p.productName")
    Page<SalesRecord> findSalesDataByDateRangeWithPaging(@Param("startDate") LocalDate startDate, 
                                                         @Param("endDate") LocalDate endDate, 
                                                         Pageable pageable);

    @Query("SELECT sr FROM SalesRecord sr " +
            "JOIN FETCH sr.salesLocation sl " +
            "JOIN FETCH sr.product p " +
            "WHERE sr.salesLocation.locationId = :locationId " +
            "AND sr.salesDate BETWEEN :startDate AND :endDate " +
            "ORDER BY sr.salesDate DESC, p.productName")
    Page<SalesRecord> findSalesDataByLocationAndDateRangeWithPaging(@Param("locationId") Long locationId,
                                                                    @Param("startDate") LocalDate startDate,
                                                                    @Param("endDate") LocalDate endDate,
                                                                    Pageable pageable);

    // 그래프용 일별 집계 쿼리
    @Query("SELECT sr.salesDate, SUM(sr.salesAmount), SUM(sr.actualSales), SUM(sr.quantity) " +
            "FROM SalesRecord sr " +
            "WHERE sr.salesDate BETWEEN :startDate AND :endDate " +
            "AND (:locationId IS NULL OR sr.salesLocation.locationId = :locationId) " +
            "GROUP BY sr.salesDate " +
            "ORDER BY sr.salesDate")
    List<Object[]> findDailySalesChart(@Param("startDate") LocalDate startDate,
                                       @Param("endDate") LocalDate endDate,
                                       @Param("locationId") Long locationId);

}
