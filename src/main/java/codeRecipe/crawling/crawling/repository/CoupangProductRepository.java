package codeRecipe.crawling.crawling.repository;

import codeRecipe.crawling.crawling.domain.CoupangProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CoupangProductRepository extends JpaRepository<CoupangProduct, Long> {

    Optional<CoupangProduct> findByVendorItemId(Long vendorItemId);

    List<CoupangProduct> findBySellerProductId(Long sellerProductId);
}
