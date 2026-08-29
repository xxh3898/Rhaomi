package kr.co.rhaomi.backend.content;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.co.rhaomi.backend.shop.BusinessHoursInvalidException;
import kr.co.rhaomi.backend.shop.ShopMediaRelationInvalidException;
import kr.co.rhaomi.backend.shop.ShopSettingsInvalidRequestException;
import kr.co.rhaomi.backend.shop.ShopSettingsNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class ContentExceptionHandlerTests {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new ContentExceptionHandler())
                .build();
    }

    @Test
    void should_returnGeneric500WithoutDetails_when_databaseAccessFails() throws Exception {
        mockMvc.perform(get("/database-failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("database connection detail"))));
    }

    @Test
    void should_returnNoticeWindowCode_when_noticeWindowIsInvalid() throws Exception {
        mockMvc.perform(get("/notice-window-invalid"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NOTICE_WINDOW_INVALID"));
    }

    @Test
    void should_returnShopSettingsNotFoundCode_when_singletonIsUninitialized() throws Exception {
        mockMvc.perform(get("/shop-settings-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHOP_SETTINGS_NOT_FOUND"));
    }

    @Test
    void should_returnBusinessHoursCode_when_hoursAreInvalid() throws Exception {
        mockMvc.perform(get("/business-hours-invalid"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BUSINESS_HOURS_INVALID"));
    }

    @Test
    void should_returnFixedInvalidRequestWithoutDetails_when_shopSettingsValidationFails()
            throws Exception {
        mockMvc.perform(get("/shop-settings-invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(content().string(not(containsString("validation detail"))));
    }

    @Test
    void should_returnShopMediaRelationCode_when_relationIsInvalid() throws Exception {
        mockMvc.perform(get("/shop-media-relation-invalid"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SHOP_MEDIA_RELATION_INVALID"));
    }

    @RestController
    static class FailingController {

        @GetMapping("/database-failure")
        void fail() {
            throw new DataAccessResourceFailureException("database connection detail");
        }

        @GetMapping("/notice-window-invalid")
        void invalidWindow() {
            throw new NoticeWindowInvalidException();
        }

        @GetMapping("/shop-settings-not-found")
        void shopSettingsNotFound() {
            throw new ShopSettingsNotFoundException();
        }

        @GetMapping("/business-hours-invalid")
        void businessHoursInvalid() {
            throw new BusinessHoursInvalidException();
        }

        @GetMapping("/shop-settings-invalid")
        void shopSettingsInvalid() {
            throw new ShopSettingsInvalidRequestException();
        }

        @GetMapping("/shop-media-relation-invalid")
        void shopMediaRelationInvalid() {
            throw new ShopMediaRelationInvalidException();
        }
    }
}
