// ============================================
// E.T.S Admin App - Enhanced JavaScript
// ============================================

// Initialize app data
const AppState = {
    logs: [],
    allLogs: [],
    maxLogs: 100,
    stats: {
        smsSent: 0,
        ussdRequests: 0,
        smsRead: 0
    },
    currentFilter: 'all'
};

// ============================================
// INITIALIZATION
// ============================================
document.addEventListener('DOMContentLoaded', () => {
    console.log('E.T.S Admin App Initializing...');

    // Set up device callbacks
    setDeviceCallbacks({
        onUSSDResponse: (msg) => addLog('Response', msg, 'Response'),
        onSMSSent: (msg) => addLog('SMS', msg, 'SMS'),
        onSMSRead: (msg) => addLog('SMS', msg, 'SMS'),
        onCallInitiated: (msg) => addLog('Call', msg, 'Call'),
        onError: (error) => addLog('Error', error.message, 'Error')
    });

    initTabs();
    initCharCounter();
    initUSSD();
    initSMSSender();
    initSMSReader();
    initCallManager();
    initButtons();
    updateTime();
    
    // Update time every second
    setInterval(updateTime, 1000);

    // Add startup logs
    const deviceInfo = getDeviceInfo();
    addLog('System', '✅ E.T.S Admin App Started', 'System');
    addLog('System', `📱 Device: ${deviceInfo.platform}`, 'System');
    addLog('System', `🔌 Connection: ${deviceInfo.connected ? 'Connected' : 'Disconnected'}`, 'System');
    addLog('System', '🚀 Ready for operations', 'System');

    console.log('E.T.S Admin App Initialized Successfully');
});

// ============================================
// TIME UPDATE
// ============================================
function updateTime() {
    const now = new Date();
    const time = now.toLocaleTimeString();
    document.getElementById('timeStatus').textContent = '⏰ ' + time;
}

// ============================================
// TAB NAVIGATION
// ============================================
function initTabs() {
    const navBtns = document.querySelectorAll('.nav-btn');
    const tabContents = document.querySelectorAll('.tab-content');

    navBtns.forEach(btn => {
        btn.addEventListener('click', () => {
            const tabId = btn.getAttribute('data-tab');
            
            navBtns.forEach(b => b.classList.remove('active'));
            tabContents.forEach(tab => tab.classList.remove('active'));
            
            btn.classList.add('active');
            document.getElementById(tabId).classList.add('active');
            
            if (tabId === 'logs') {
                displayLogs('all');
            }
        });
    });
}

// ============================================
// CHARACTER COUNTER
// ============================================
function initCharCounter() {
    const messageInput = document.getElementById('messageInput');
    const charCount = document.getElementById('charCount');

    messageInput.addEventListener('input', () => {
        charCount.textContent = messageInput.value.length;
    });
}

// ============================================
// BUTTONS INITIALIZATION
// ============================================
function initButtons() {
    document.getElementById('clearBtn').addEventListener('click', clearLogs);
}

// ============================================
// LOGGING SYSTEM
// ============================================
function addLog(message, details = '', type = 'Info') {
    const timestamp = new Date().toLocaleTimeString();
    
    const logEntry = {
        type: type,
        message: message,
        details: details,
        time: timestamp,
        fullMessage: `${message}${details ? ' - ' + details : ''}`
    };

    AppState.allLogs.push(logEntry);
    
    if (AppState.currentFilter === 'all' || AppState.currentFilter === type) {
        AppState.logs.push(logEntry);
        
        if (AppState.logs.length > AppState.maxLogs) {
            AppState.logs.shift();
        }
        
        displayLog(logEntry);
    }

    // Update footer status
    document.getElementById('footerStatus').textContent = message;
}

function displayLog(logEntry) {
    const resultBox = document.getElementById('resultBox');
    const resultItem = document.createElement('div');
    resultItem.className = 'result-item';

    resultItem.innerHTML = `
        <span class="result-time">[${logEntry.time}]</span>
        <span class="result-type">${logEntry.type}</span>
        <span class="result-message">${escapeHtml(logEntry.fullMessage)}</span>
    `;

    resultBox.appendChild(resultItem);
    resultBox.scrollTop = resultBox.scrollHeight;
}

function displayLogs(filter) {
    const resultBox = document.getElementById('resultBox');
    resultBox.innerHTML = '';

    const logsToDisplay = filter === 'all' 
        ? AppState.allLogs 
        : AppState.allLogs.filter(log => log.type === filter);

    logsToDisplay.forEach(logEntry => {
        const resultItem = document.createElement('div');
        resultItem.className = 'result-item';

        resultItem.innerHTML = `
            <span class="result-time">[${logEntry.time}]</span>
            <span class="result-type">${logEntry.type}</span>
            <span class="result-message">${escapeHtml(logEntry.fullMessage)}</span>
        `;

        resultBox.appendChild(resultItem);
    });

    resultBox.scrollTop = resultBox.scrollHeight;
}

function filterLogs(type) {
    AppState.currentFilter = type;
    
    document.querySelectorAll('.filter-btn').forEach(btn => {
        btn.classList.remove('active');
    });
    
    event.target.classList.add('active');
    displayLogs(type);
}

function clearLogs() {
    AppState.logs = [];
    AppState.allLogs = [];
    document.getElementById('resultBox').innerHTML = '';
    addLog('System', 'Logs cleared', 'System');
}

function exportLogs() {
    const logsText = AppState.allLogs.map(log => 
        `[${log.time}] ${log.type}: ${log.fullMessage}`
    ).join('\n');

    const blob = new Blob([logsText], { type: 'text/plain' });
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `ETS-Logs-${new Date().toISOString().slice(0,10)}.txt`;
    a.click();
    window.URL.revokeObjectURL(url);
    
    addLog('Export', `Exported ${AppState.allLogs.length} log entries`, 'System');
}

// ============================================
// USSD FUNCTIONALITY
// ============================================
function initUSSD() {
    const ussdBtn = document.getElementById('ussdBtn');
    const ussdInput = document.getElementById('ussdInput');

    ussdBtn.addEventListener('click', sendUSSD);
    ussdInput.addEventListener('keypress', (e) => {
        if (e.key === 'Enter') sendUSSD();
    });
}

function setUSSD(code) {
    document.getElementById('ussdInput').value = code;
}

function sendUSSD() {
    const ussdCode = document.getElementById('ussdInput').value.trim();

    if (!ussdCode) {
        addLog('Error', 'Please enter a USSD code', 'Error');
        return;
    }

    addLog('USSD', `Sending: ${ussdCode}`, 'USSD');
    AppState.stats.ussdRequests++;
    updateStats();

    deviceSendUSSD(ussdCode)
        .then(result => {
            addLog('System', `USSD sent successfully`, 'System');
        })
        .catch(error => {
            addLog('Error', `USSD Error: ${error.message}`, 'Error');
        });

    document.getElementById('ussdInput').value = '';
}

// ============================================
// SMS SENDER FUNCTIONALITY
// ============================================
function initSMSSender() {
    const smsBtn = document.getElementById('smsBtn');
    const messageInput = document.getElementById('messageInput');

    smsBtn.addEventListener('click', sendSMS);
    
    messageInput.addEventListener('keydown', (e) => {
        if (e.ctrlKey && e.key === 'Enter') {
            sendSMS();
        }
    });
}

function setTemplate(template) {
    document.getElementById('messageInput').value = template;
    document.getElementById('charCount').textContent = template.length;
}

function sendSMS() {
    const phone = document.getElementById('phoneInput').value.trim();
    const message = document.getElementById('messageInput').value.trim();

    if (!phone) {
        addLog('Error', 'Please enter a phone number', 'Error');
        return;
    }

    if (!message) {
        addLog('Error', 'Please enter a message', 'Error');
        return;
    }

    if (message.length > 160) {
        addLog('Warning', `Message is ${message.length} characters`, 'Warning');
    }

    addLog('SMS', `To: ${phone}`, 'SMS');
    AppState.stats.smsSent++;
    updateStats();

    deviceSendSMS(phone, message)
        .then(result => {
            addLog('System', 'SMS sent successfully', 'System');
        })
        .catch(error => {
            addLog('Error', `SMS Error: ${error.message}`, 'Error');
        });

    document.getElementById('phoneInput').value = '';
    document.getElementById('messageInput').value = '';
    document.getElementById('charCount').textContent = '0';
}

// ============================================
// SMS READER FUNCTIONALITY
// ============================================
function initSMSReader() {
    document.getElementById('readBtn').addEventListener('click', readSMS);
    document.getElementById('readAllBtn').addEventListener('click', function() {
        addLog('SMS', 'Reading all SMS messages...', 'SMS');
        if (window.AndroidUSSD && typeof window.AndroidUSSD.readSms === 'function') {
            window.AndroidUSSD.readSms();
        }
    });
}

function readSMS() {
    addLog('SMS', 'Fetching last 10 SMS messages...', 'SMS');
    AppState.stats.smsRead++;
    updateStats();

    deviceReadSMS(10)
        .then(result => {
            addLog('System', 'SMS read successfully', 'System');
        })
        .catch(error => {
            addLog('Error', `Read SMS Error: ${error.message}`, 'Error');
        });
}

// ============================================
// CALL MANAGER FUNCTIONALITY
// ============================================
function initCallManager() {
    const callBtn = document.getElementById('callBtn');
    const callInput = document.getElementById('callInput');

    callBtn.addEventListener('click', makeCall);
    callInput.addEventListener('keypress', (e) => {
        if (e.key === 'Enter') makeCall();
    });
}

function makeCall() {
    const phone = document.getElementById('callInput').value.trim();

    if (!phone) {
        addLog('Error', 'Please enter a phone number', 'Error');
        return;
    }

    addLog('Call', `Initiating call to: ${phone}`, 'Call');

    deviceMakeCall(phone)
        .then(result => {
            addLog('System', 'Call initiated successfully', 'System');
        })
        .catch(error => {
            addLog('Error', `Call Error: ${error.message}`, 'Error');
        });

    document.getElementById('callInput').value = '';
}

// ============================================
// DASHBOARD & STATS
// ============================================
function updateStats() {
    document.getElementById('smsSentCount').textContent = AppState.stats.smsSent;
    document.getElementById('ussdCount').textContent = AppState.stats.ussdRequests;
    document.getElementById('smsReadCount').textContent = AppState.stats.smsRead;
}

function refreshStats() {
    addLog('System', 'Stats refreshed', 'System');
    updateStats();
}

function quickTest() {
    addLog('System', 'Running connectivity test...', 'System');
    
    const deviceInfo = getDeviceInfo();
    addLog('System', `Android bridge: ${deviceInfo.connected ? 'Connected' : 'Disconnected'}`, 'System');
    addLog('System', `USSD Support: ${deviceInfo.hasUSSD ? 'Available' : 'Not Available'}`, 'System');
    addLog('System', `SMS Support: ${deviceInfo.hasSMS ? 'Available' : 'Not Available'}`, 'System');
    addLog('System', `Call Support: ${deviceInfo.hasCall ? 'Available' : 'Not Available'}`, 'System');
    addLog('System', 'Test completed', 'System');
}

// ============================================
// ANDROID BRIDGE - RECEIVE RESULTS
// ============================================
window.showResult = function(message) {
    addLog('Response', message, 'Response');
    
    // Try to parse and display SMS data
    if (message.includes('From:')) {
        displaySMSPreview(message);
    }

    // Also call the device handler
    if (window.deviceHandleResult) {
        window.deviceHandleResult(message);
    }
};

function displaySMSPreview(message) {
    const preview = document.getElementById('smsPreview');
    
    const lines = message.split('\n\n');
    lines.forEach(sms => {
        if (sms.trim()) {
            const smsDiv = document.createElement('div');
            smsDiv.className = 'sms-item';
            smsDiv.innerHTML = `<div class="sms-text">${escapeHtml(sms.trim())}</div>`;
            preview.appendChild(smsDiv);
        }
    });
    
    preview.scrollTop = preview.scrollHeight;
}

// ============================================
// UTILITY FUNCTIONS
// ============================================
function escapeHtml(text) {
    const map = {
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&#039;'
    };
    return text.replace(/[&<>"']/g, m => map[m]);
}

// ============================================
// ERROR HANDLING
// ============================================
window.addEventListener('error', (event) => {
    addLog('Error', event.message, 'Error');
});

