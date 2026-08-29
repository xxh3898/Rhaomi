package kr.co.rhaomi.backend.shop;

import jakarta.validation.Valid;
import java.net.URI;
import kr.co.rhaomi.backend.auth.AdminPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/shop-settings")
public class ShopSettingsAdminController {

    private static final URI SHOP_SETTINGS_URI = URI.create("/api/admin/shop-settings");

    private final ShopSettingsAdminService shopSettingsAdminService;

    public ShopSettingsAdminController(ShopSettingsAdminService shopSettingsAdminService) {
        this.shopSettingsAdminService = shopSettingsAdminService;
    }

    @GetMapping
    public ShopSettingsResponse get() {
        return shopSettingsAdminService.get();
    }

    @PutMapping
    public ResponseEntity<ShopSettingsResponse> put(
            @Valid @RequestBody ShopSettingsRequest request,
            @AuthenticationPrincipal AdminPrincipal principal) {
        var result = shopSettingsAdminService.put(request, principal.id());
        if (result.created()) {
            return ResponseEntity.created(SHOP_SETTINGS_URI).body(result.response());
        }
        return ResponseEntity.ok(result.response());
    }
}
