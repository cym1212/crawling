package codeRecipe.crawling.crawling;


import codeRecipe.crawling.crawling.domain.SalesLocation;
import codeRecipe.crawling.crawling.repository.SalesLocationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class DataInitializer {

    private final SalesLocationRepository salesLocationRepository;

    @Bean
    @Transactional
    public CommandLineRunner initializeArcnbook() {
        return args -> {
            String locationName = "Arcnbook";
            List<String> ArcnbookRegion = List.of("수지점", "신촌점", "롯데월드몰점", "동탄호수점", "월계점", "부산아시아드점",
                    "몬드리안점", "광안리점", "아크앤북온라인", "충청점", "부산명지점", "세종점");

            List<String> existingRegions = salesLocationRepository.findAllByLocationNameAndRegionIn(locationName, ArcnbookRegion)
                    .stream()
                    .map(SalesLocation::getRegion)
                    .toList();

            List<String> newRegions = ArcnbookRegion.stream()
                    .filter(region -> !existingRegions.contains(region))
                    .toList();

            newRegions.forEach(region -> {
                SalesLocation newLocation = new SalesLocation(locationName, region);
                salesLocationRepository.save(newLocation);
            });
        };
    }
    @Bean
    @Transactional
    public CommandLineRunner initializeLibro() {
        return args -> {

            String locationName = "Libro";
            List<String> LibroRegion = List.of("수원점","상봉점","시흥점","기흥점","원주점","분당수내점","구로점(NC)","광명점","광양점");

            List<String> existingRegions = salesLocationRepository.findAllByLocationNameAndRegionIn(locationName, LibroRegion)
                    .stream()
                    .map(SalesLocation::getRegion)
                    .toList();

            List<String> newRegions = LibroRegion.stream()
                    .filter(region -> !existingRegions.contains(region))
                    .toList();

            newRegions.forEach(region -> {
                SalesLocation newLocation = new SalesLocation(locationName, region);
                salesLocationRepository.save(newLocation);
            });
        };
    }
    @Bean
    @Transactional
    public CommandLineRunner initializeHottracks() {
        return args -> {

            String locationName = "Hottracks";
            List<String> HottracksRegion = List.of("건대스타시티점", "수유점", "송도점", "합정점");

            List<String> existingRegions = salesLocationRepository.findAllByLocationNameAndRegionIn(locationName, HottracksRegion)
                    .stream()
                    .map(SalesLocation::getRegion)
                    .toList();

            List<String> newRegions = HottracksRegion.stream()
                    .filter(region -> !existingRegions.contains(region))
                    .toList();

            newRegions.forEach(region -> {
                SalesLocation newLocation = new SalesLocation(locationName, region);
                salesLocationRepository.save(newLocation);
            });
        };
    }



}
