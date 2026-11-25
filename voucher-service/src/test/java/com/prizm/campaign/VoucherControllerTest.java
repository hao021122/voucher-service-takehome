package com.prizm.campaign;

import com.prizm.campaign.controller.VoucherController;
import com.prizm.campaign.dto.RedeemResponse;
import com.prizm.campaign.exception.VoucherLimitReachException;
import com.prizm.campaign.service.VoucherService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VoucherController.class)
class VoucherControllerTest {

    private static final String LIMIT_MESSAGE =
            "User has reached the redemption limit for this campaign (2)";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VoucherService voucherService;

    @Test
    @DisplayName("Redeem endpoint returns 200 with OK result for a valid request")
    void redeem_validRequest_returns200WithOkResult() throws Exception {
        when(voucherService.redeem("RAYA-0001", "user-1"))
                .thenReturn(RedeemResponse.ok("RAYA-0001", 99));

        mockMvc.perform(post("/vouchers/RAYA-0001/redeem")
                        .param("userId", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("OK"))
                .andExpect(jsonPath("$.voucherCode").value("RAYA-0001"))
                .andExpect(jsonPath("$.remainingStock").value(99));
    }

    @Test
    @DisplayName("Redeem endpoint returns 409 with reason when limit is reached")
    void redeem_userAtLimit_returns409WithMessage() throws Exception {
        when(voucherService.redeem("RAYA-0001", "user-99"))
                .thenThrow(new VoucherLimitReachException(LIMIT_MESSAGE));

        mockMvc.perform(post("/vouchers/RAYA-0001/redeem")
                        .param("userId", "user-99"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(LIMIT_MESSAGE));
    }
}
