/**
 * ApiService - HTTP API wrapper for the Joy Creator Remote page.
 *
 * Calls the phone's own HTTP server (the page is served from it on the local Wi-Fi).
 */
class ApiService {
    constructor(baseUrl) {
        this.localBaseUrl = baseUrl || CONFIG.BASE_URL;
        this.statusCache = null;
        this.lastFetchTime = 0;
    }
    
    // =========================================================================
    // Mode
    // =========================================================================

    /**
     * Cloud mode no longer exists; the page only talks to the phone it was served from.
     * Kept so existing callers keep working.
     */
    isCloudMode() {
        return false;
    }

    /**
     * Get the base URL (the phone's HTTP server)
     */
    getBaseUrl() {
        return this.localBaseUrl;
    }

    // =========================================================================
    // Headers
    // =========================================================================
    
    /**
     * Get headers with auth token injected
     */
    getHeaders() {
        const headers = {
            'Content-Type': 'application/json'
        };
        
        // Inject auth token if available
        if (typeof authService !== 'undefined') {
            const authHeaders = authService.getAuthHeaders();
            Object.assign(headers, authHeaders);
        }
        
        return headers;
    }
    
    // =========================================================================
    // Status API
    // =========================================================================
    
    /**
     * GET /status - Fetch server status
     * @returns {Promise<Object>} Status data
     */
    async getStatus() {
        return this._getLocalStatus();
    }
    
    /**
     * Local mode: Fetch status from phone HTTP server
     * 
     * FIX for Chromium encoding issue:
     * Some Android HTTP servers (NanoHTTPD) don't set charset=utf-8 in Content-Type.
     * Chromium browsers are strict about encoding and fail with "Unexpected token..."
     * Firefox is lenient and works fine.
     * 
     * Solution: Get response as text first, strip BOM if present, then parse JSON manually.
     * This ensures cross-browser compatibility.
     */
    async _getLocalStatus() {
        try {
            const startTime = performance.now();
            console.log(`📱 [/status] Dashboard sending request to ${this.localBaseUrl}/status`);
            
            const response = await fetch(`${this.localBaseUrl}/status`, {
                method: 'GET',
                headers: this.getHeaders()
            });
            
            const fetchTime = performance.now() - startTime;
            console.log(`📡 [/status] Response received in ${fetchTime.toFixed(2)}ms, status: ${response.status}`);
            
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }
            
            // CRITICAL: Get response as text first to handle encoding issues
            // This prevents Chromium's strict JSON parser from failing on charset mismatches
            let responseText = await response.text();
            
            // Strip UTF-8 BOM (Byte Order Mark: EF BB BF) if present
            // Some servers inadvertently prepend BOM to JSON responses
            if (responseText.charCodeAt(0) === 0xFEFF) {
                console.warn('⚠️ [/status] Detected and removing UTF-8 BOM from response');
                responseText = responseText.slice(1);
            }
            
            // Log first 100 chars for debugging encoding issues
            const preview = responseText.substring(0, 100);
            console.log(`📋 [/status] Response preview: ${preview}${responseText.length > 100 ? '...' : ''}`);
            
            // Parse JSON manually with error context
            try {
                this.statusCache = JSON.parse(responseText);
            } catch (parseError) {
                // Enhanced error logging for debugging
                console.error('❌ [/status] JSON parse error. Response length:', responseText.length);
                console.error('❌ [/status] First 200 chars:', responseText.substring(0, 200));
                console.error('❌ [/status] Parse error details:', parseError.message);
                throw new Error(`JSON parse error: ${parseError.message}. Response length: ${responseText.length}`);
            }
            
            this.statusCache.cloudMode = false;
            this.lastFetchTime = Date.now();
            
            console.log(`✅ [/status] 📱 Local: state=${this.statusCache.streaming ? 'streaming' : 'idle'}, clients=${this.statusCache.totalConnectedClients}`);
            
            return this.statusCache;
        } catch (error) {
            console.error('❌ [/status] Failed to fetch local status:', error);
            throw error;
        }
    }
    
    // =========================================================================
    // Command APIs
    // =========================================================================
    
    /**
     * POST /torch/toggle - Toggle flashlight
     * @returns {Promise<Object>} Response data
     */
    async toggleTorch() {
        try {
            const response = await fetch(`${this.localBaseUrl}/torch/toggle`, {
                method: 'POST',
                headers: this.getHeaders()
            });
            
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }
            
            return await this._parseJsonResponse(response, 'torch/toggle');
        } catch (error) {
            console.error('Failed to toggle torch:', error);
            throw error;
        }
    }
    
    /**
     * POST /recording/pause - Pause active recording
     * @returns {Promise<Object>} Response data with {status, action, isRecording, isPaused}
     */
    async pauseRecording() {
        return this.post('/recording/pause', {});
    }

    /**
     * POST /recording/resume - Resume paused recording
     * @returns {Promise<Object>} Response data with {status, action, isRecording, isPaused}
     */
    async resumeRecording() {
        return this.post('/recording/resume', {});
    }

    /**
     * POST /camera/switch - Switch the active camera (BACK ↔ FRONT).
     * During active recording the switch happens live; otherwise the preference is saved.
     * @param {string|null} target - 'back', 'front', or null to toggle
     * @returns {Promise<Object>} Response data
     */
    async switchCamera(target = null) {
        const params = target ? { to: target.toUpperCase() } : {};
        return this.post('/camera/switch', params);
    }

    /**
     * Toggle camera (BACK ↔ FRONT) – convenience alias for switchCamera()
     * @returns {Promise<Object>}
     */
    async toggleCamera() {
        return this.switchCamera(null);
    }

    /**
     * POST /config/zoom – Set zoom ratio and optional pan.
     * @param {number} ratio      Zoom magnification (≥ 1.0)
     * @param {number} [panX=0]   Horizontal pan -1.0…+1.0 (0 = centre)
     * @param {number} [panY=0]   Vertical pan   -1.0…+1.0 (0 = centre)
     */
    async setZoom(ratio, panX = null, panY = null) {
        const body = { ratio };
        // Only include pan when explicitly provided – avoids wiping phone's saved pan
        if (panX !== null && panY !== null) {
            body.panX = panX;
            body.panY = panY;
        }
        return this.post('/config/zoom', body);
    }

    /**
     * POST /config/exposure – Set exposure compensation in EV steps.
     * @param {number} ev  Integer −5…+5
     */
    async setExposure(ev) {
        return this.post('/config/exposure', { ev: Math.round(ev) });
    }

    /**
     * POST /config/mirror – Set or toggle front-camera horizontal mirror.
     * @param {boolean|null} enabled  true/false to set explicitly, null to toggle
     */
    async setMirror(enabled = null) {
        const params = (enabled !== null) ? { enabled } : {};
        return this.post('/config/mirror', params);
    }

    /**
     * POST /config/aeLock – Toggle AE (Auto-Exposure) lock.
     */
    async toggleAeLock() {
        return this.post('/config/aeLock', {});
    }

    /**
     * Generic POST request
     * @param {string} endpoint - API endpoint path
     * @param {Object} data - Request body data
     * @returns {Promise<Object>} Response data
     */
    async post(endpoint, data = {}) {
        try {
            console.log(`📱 [POST] ${endpoint}`, data);
            const response = await fetch(`${this.localBaseUrl}${endpoint}`, {
                method: 'POST',
                headers: this.getHeaders(),
                body: JSON.stringify(data)
            });
            
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }
            
            const responseData = await this._parseJsonResponse(response, endpoint);
            console.log(`✅ [POST] ${endpoint} response:`, responseData);
            return responseData;
        } catch (error) {
            console.error(`❌ [POST] ${endpoint} failed:`, error);
            throw error;
        }
    }
    
    /**
     * Parse JSON response with defensive encoding handling
     * Handles UTF-8 BOM and charset mismatches across all browsers
     * @private
     * @param {Response} response - Fetch response object
     * @param {string} endpoint - Endpoint name for logging
     * @returns {Promise<Object>} Parsed JSON
     */
    async _parseJsonResponse(response, endpoint) {
        let responseText = await response.text();
        
        // Strip UTF-8 BOM if present
        if (responseText.charCodeAt(0) === 0xFEFF) {
            console.warn(`⚠️ [${endpoint}] Detected and removing UTF-8 BOM from response`);
            responseText = responseText.slice(1);
        }
        
        try {
            return JSON.parse(responseText);
        } catch (parseError) {
            console.error(`❌ [${endpoint}] JSON parse error. Response length: ${responseText.length}`);
            console.error(`❌ [${endpoint}] First 200 chars: ${responseText.substring(0, 200)}`);
            throw new Error(`JSON parse error in ${endpoint}: ${parseError.message}`);
        }
    }
    
    // =========================================================================
    // HLS URL
    // =========================================================================

    /**
     * Get HLS stream URL for video player
     */
    getHlsUrl() {
        return `${this.localBaseUrl}${CONFIG.ENDPOINTS.HLS}`;
    }

    // =========================================================================
    // Cache
    // =========================================================================
    
    /**
     * Get cached status (no network call)
     * @returns {Object|null} Cached status or null
     */
    getCachedStatus() {
        return this.statusCache;
    }
    
    /**
     * Check if cache is fresh (< 5 seconds old)
     * @returns {boolean} True if cache is fresh
     */
    isCacheFresh() {
        if (!this.statusCache || !this.lastFetchTime) return false;
        return (Date.now() - this.lastFetchTime) < 5000;
    }
}

// Global singleton
const apiService = new ApiService();

/**
 * Get the API service (for backward compatibility)
 * Returns the shared apiService
 */
function getApiService() {
    return apiService;
}
