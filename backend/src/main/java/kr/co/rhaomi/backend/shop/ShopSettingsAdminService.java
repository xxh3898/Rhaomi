package kr.co.rhaomi.backend.shop;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import kr.co.rhaomi.backend.media.MediaAssetRepository;
import kr.co.rhaomi.backend.media.MediaStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShopSettingsAdminService {

    private final ShopSettingsRepository shopSettingsRepository;
    private final MediaAssetRepository mediaAssetRepository;

    public ShopSettingsAdminService(
            ShopSettingsRepository shopSettingsRepository,
            MediaAssetRepository mediaAssetRepository) {
        this.shopSettingsRepository = shopSettingsRepository;
        this.mediaAssetRepository = mediaAssetRepository;
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
        validateMediaRelations(values);
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

    private void validateMediaRelations(ShopSettingsValues values) {
        var relationIds = Stream.of(
                values.heroImageId(), values.groomerImageId(), values.ogImageId())
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (relationIds.isEmpty()) {
            return;
        }

        var activeIds = mediaAssetRepository.findAllById(relationIds).stream()
                .filter(mediaAsset -> mediaAsset.getStatus() == MediaStatus.ACTIVE)
                .map(mediaAsset -> mediaAsset.getId())
                .collect(Collectors.toSet());
        if (!activeIds.containsAll(relationIds)) {
            throw new ShopMediaRelationInvalidException();
        }
    }

    public record PutResult(ShopSettingsResponse response, boolean created) {}
}
