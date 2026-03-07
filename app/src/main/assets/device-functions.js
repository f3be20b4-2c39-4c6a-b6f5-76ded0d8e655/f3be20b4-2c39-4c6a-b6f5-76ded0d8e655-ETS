// ============================================
// E.T.S Device Functions - Android Bridge
// ============================================
// This file handles all Android device interactions
// Keep separate from UI logic

// ============================================
// DEVICE STATE VARIABLES
// ============================================
const DeviceConfig = {
    connected: false,
    bridgeName: 'AndroidUSSD',
    version: '1.0.0',
    platform: 'Android 4.4.2+'
};

const DeviceCallbacks = {
    onUSSDResponse: null,
    onSMSSent: null,
    onSMSRead: null,
    onCallInitiated: null,
    onError: null
};

// ============================================
// DEVICE CONNECTION CHECK
// ============================================
function checkDeviceConnection() {
    if (window.AndroidUSSD) {
        DeviceConfig.connected = true;
        console.log('✅ Android Bridge Connected');
        return true;
    } else {
        DeviceConfig.connected = false;
        console.warn('❌ Android Bridge Not Available');
        return false;
    }
}

function isDeviceConnected() {
    return DeviceConfig.connected || window.AndroidUSSD !== undefined;
}

// ============================================
// USSD FUNCTIONS
// ============================================
function deviceSendUSSD(ussdCode) {
    return new Promise((resolve, reject) => {
        if (!ussdCode || ussdCode.trim() === '') {
            reject(new Error('USSD code cannot be empty'));
            return;
        }

        if (!isDeviceConnected()) {
            reject(new Error('Device not connected'));
            return;
        }

        try {
            if (typeof window.AndroidUSSD.runUssd === 'function') {
                window.AndroidUSSD.runUssd(ussdCode);
                resolve({
                    status: 'sent',
                    code: ussdCode,
                    timestamp: new Date().toISOString()
                });

                if (DeviceCallbacks.onUSSDResponse) {
                    DeviceCallbacks.onUSSDResponse(`USSD sent: ${ussdCode}`);
                }
            } else {
                reject(new Error('USSD function not available'));
            }
        } catch (error) {
            reject(error);
        }
    });
}

// ============================================
// SMS FUNCTIONS
// ============================================
function deviceSendSMS(phoneNumber, messageText) {
    return new Promise((resolve, reject) => {
        if (!phoneNumber || phoneNumber.trim() === '') {
            reject(new Error('Phone number cannot be empty'));
            return;
        }

        if (!messageText || messageText.trim() === '') {
            reject(new Error('Message cannot be empty'));
            return;
        }

        if (!isDeviceConnected()) {
            reject(new Error('Device not connected'));
            return;
        }

        try {
            if (typeof window.AndroidUSSD.sendSms === 'function') {
                window.AndroidUSSD.sendSms(phoneNumber, messageText);
                resolve({
                    status: 'sent',
                    phone: phoneNumber,
                    message: messageText,
                    length: messageText.length,
                    timestamp: new Date().toISOString()
                });

                if (DeviceCallbacks.onSMSSent) {
                    DeviceCallbacks.onSMSSent(`SMS sent to ${phoneNumber}`);
                }
            } else {
                reject(new Error('SMS function not available'));
            }
        } catch (error) {
            reject(error);
        }
    });
}

function deviceReadSMS(limit = 10) {
    return new Promise((resolve, reject) => {
        if (!isDeviceConnected()) {
            reject(new Error('Device not connected'));
            return;
        }

        try {
            if (typeof window.AndroidUSSD.readSms === 'function') {
                window.AndroidUSSD.readSms();
                resolve({
                    status: 'reading',
                    limit: limit,
                    timestamp: new Date().toISOString()
                });

                if (DeviceCallbacks.onSMSRead) {
                    DeviceCallbacks.onSMSRead(`Reading SMS (limit: ${limit})`);
                }
            } else {
                reject(new Error('SMS read function not available'));
            }
        } catch (error) {
            reject(error);
        }
    });
}

// ============================================
// CALL FUNCTIONS
// ============================================
function deviceMakeCall(phoneNumber) {
    return new Promise((resolve, reject) => {
        if (!phoneNumber || phoneNumber.trim() === '') {
            reject(new Error('Phone number cannot be empty'));
            return;
        }

        // Validate phone number format
        const phoneRegex = /^[\d\s\-\+\(\)]+$/;
        if (!phoneRegex.test(phoneNumber)) {
            reject(new Error('Invalid phone number format'));
            return;
        }

        if (!isDeviceConnected()) {
            reject(new Error('Device not connected'));
            return;
        }

        try {
            if (typeof window.AndroidUSSD.makeCall === 'function') {
                window.AndroidUSSD.makeCall(phoneNumber);
                resolve({
                    status: 'initiated',
                    phone: phoneNumber,
                    timestamp: new Date().toISOString()
                });

                if (DeviceCallbacks.onCallInitiated) {
                    DeviceCallbacks.onCallInitiated(`Call initiated to ${phoneNumber}`);
                }
            } else {
                // Fallback: Use intent URI if makeCall not available
                console.warn('makeCall not available, attempting fallback');
                reject(new Error('Call function not available'));
            }
        } catch (error) {
            reject(error);
        }
    });
}

// ============================================
// GET DEVICE INFO
// ============================================
function getDeviceInfo() {
    return {
        connected: isDeviceConnected(),
        bridgeName: DeviceConfig.bridgeName,
        platform: DeviceConfig.platform,
        version: DeviceConfig.version,
        hasUSSD: typeof window.AndroidUSSD !== 'undefined' && typeof window.AndroidUSSD.runUssd === 'function',
        hasSMS: typeof window.AndroidUSSD !== 'undefined' && typeof window.AndroidUSSD.sendSms === 'function',
        hasCall: typeof window.AndroidUSSD !== 'undefined' && typeof window.AndroidUSSD.makeCall === 'function',
        timestamp: new Date().toISOString()
    };
}

// ============================================
// ERROR HANDLING
// ============================================
function handleDeviceError(error, context = '') {
    const errorObj = {
        message: error.message || 'Unknown error',
        context: context,
        timestamp: new Date().toISOString(),
        deviceConnected: isDeviceConnected()
    };

    console.error(`[Device Error - ${context}]`, errorObj);

    if (DeviceCallbacks.onError) {
        DeviceCallbacks.onError(errorObj);
    }

    return errorObj;
}

// ============================================
// SET CALLBACKS
// ============================================
function setDeviceCallbacks(callbacks) {
    if (callbacks.onUSSDResponse) {
        DeviceCallbacks.onUSSDResponse = callbacks.onUSSDResponse;
    }
    if (callbacks.onSMSSent) {
        DeviceCallbacks.onSMSSent = callbacks.onSMSSent;
    }
    if (callbacks.onSMSRead) {
        DeviceCallbacks.onSMSRead = callbacks.onSMSRead;
    }
    if (callbacks.onCallInitiated) {
        DeviceCallbacks.onCallInitiated = callbacks.onCallInitiated;
    }
    if (callbacks.onError) {
        DeviceCallbacks.onError = callbacks.onError;
    }
}

// ============================================
// GLOBAL CALLBACK FOR ANDROID RESULTS
// ============================================
window.deviceHandleResult = function(message) {
    console.log('Device Result Received:', message);
    
    // This will be called by Android
    if (DeviceCallbacks.onUSSDResponse) {
        DeviceCallbacks.onUSSDResponse(message);
    }
};

// ============================================
// INITIALIZE DEVICE FUNCTIONS
// ============================================
function initializeDeviceFunctions() {
    checkDeviceConnection();
    const info = getDeviceInfo();
    console.log('Device Functions Initialized:', info);
    return info;
}

// Auto-initialize when script loads
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initializeDeviceFunctions);
} else {
    initializeDeviceFunctions();
}
