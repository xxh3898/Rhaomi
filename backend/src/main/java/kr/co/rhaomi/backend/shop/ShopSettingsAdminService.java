package kr.co.rhaomi.backend.shop;

import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShopSettingsAdminService {

    private final ShopSettingsRepository shopSettingsRepository;

    public ShopSettingsAdminService(ShopSettingsRepository shopSettingsRepository) {
        this.shopSettingsRepository = shopSettingsRepository;
    }

    @Transactional(readOnly = true)
    public ShopSettingsResponse get() {
        return ShopSettingsResponse.from(shopSettingsRepository
                .findBySingletonKeyTrue()
                .orElseThrow(ShopSettingsNotFoundException::new));
    }

    @Transactional
    public PutResult put(ShopSettingsRequest request, UUID actorId) {
        Objects.requireNonNull(actorId, "actorId");
        var values = ShopSettingsValues.from(request);
        var existing = shopSettingsRepository.findBySingletonKeyTrue();

        if (existing.isEmpty()) {
            var created = shopSettingsRepository.saveAndFlush(ShopSettings.create(values, actorId));
            return new PutResult(ShopSettingsResponse.from(created), true);
        }

        var settings = existing.orElseThrow();
        settings.update(values, actorId);
        var updated = shopSettingsRepository.saveAndFlush(settings);
        return new PutResult(ShopSettingsResponse.from(updated), false);
    }

    public record PutResult(ShopSettingsResponse response, boolean created) {}
}
