package com.prizm.campaign.exception;

public class VoucherLimitReachException extends RuntimeException {

    public VoucherLimitReachException(String errMessage) {
        super(errMessage);
    }
}
