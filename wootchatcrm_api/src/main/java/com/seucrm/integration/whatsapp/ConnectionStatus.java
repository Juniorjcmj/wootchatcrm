package com.seucrm.integration.whatsapp;

public class ConnectionStatus {
    private boolean connected;
    private String phoneNumber;
    private String qrCode;

    public ConnectionStatus() {}

    public ConnectionStatus(boolean connected, String phoneNumber, String qrCode) {
        this.connected = connected;
        this.phoneNumber = phoneNumber;
        this.qrCode = qrCode;
    }

    public boolean isConnected() { return connected; }
    public void setConnected(boolean connected) { this.connected = connected; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public String getQrCode() { return qrCode; }
    public void setQrCode(String qrCode) { this.qrCode = qrCode; }

    public static ConnectionStatusBuilder builder() {
        return new ConnectionStatusBuilder();
    }

    public static class ConnectionStatusBuilder {
        private boolean connected;
        private String phoneNumber;
        private String qrCode;

        public ConnectionStatusBuilder connected(boolean connected) {
            this.connected = connected;
            return this;
        }

        public ConnectionStatusBuilder phoneNumber(String phoneNumber) {
            this.phoneNumber = phoneNumber;
            return this;
        }

        public ConnectionStatusBuilder qrCode(String qrCode) {
            this.qrCode = qrCode;
            return this;
        }

        public ConnectionStatus build() {
            return new ConnectionStatus(connected, phoneNumber, qrCode);
        }
    }
}
