package com.seucrm.integration.whatsapp;

public class SendResult {
    private boolean success;
    private String externalMessageId;
    private String errorMessage;

    public SendResult() {}

    public SendResult(boolean success, String externalMessageId, String errorMessage) {
        this.success = success;
        this.externalMessageId = externalMessageId;
        this.errorMessage = errorMessage;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getExternalMessageId() { return externalMessageId; }
    public void setExternalMessageId(String externalMessageId) { this.externalMessageId = externalMessageId; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public static SendResultBuilder builder() {
        return new SendResultBuilder();
    }

    public static class SendResultBuilder {
        private boolean success;
        private String externalMessageId;
        private String errorMessage;

        public SendResultBuilder success(boolean success) {
            this.success = success;
            return this;
        }

        public SendResultBuilder externalMessageId(String externalMessageId) {
            this.externalMessageId = externalMessageId;
            return this;
        }

        public SendResultBuilder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public SendResult build() {
            return new SendResult(success, externalMessageId, errorMessage);
        }
    }
}
