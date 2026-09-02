package kr.co.rhaomi.backend.shop;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShopSettingsRepository extends JpaRepository<ShopSettings, UUID> {

    Optional<ShopSettings> findBySingletonKeyTrue();
}
