# Mobile App Integration Guide - Phone OTP Authentication

## For Mobile Apps Only (No Browser Flow)

This guide is specifically for mobile applications that need phone-based authentication without using browser redirects.

---

## ⚡ Quick Start

### Step 1: Request OTP

```javascript
const requestOTP = async (phoneNumber, countryCode = '+91') => {
  try {
    const response = await fetch(
      'https://auth.iheal.digital/realms/iHeal/phone-auth/request-otp',
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          phone: phoneNumber,        // e.g., "7207207260"
          regionPrefix: countryCode, // e.g., "+91"
          clientId: 'iHealMobileApp'
        })
      }
    );

    const result = await response.json();
    
    if (result.success) {
      // OTP sent successfully
      return {
        sessionId: result.sessionId,      // Save this for step 2
        phoneNumber: result.phoneNumber,   // e.g., "+917207207260"
        expiresIn: result.expiresIn        // Seconds until expiry (600)
      };
    } else {
      // Handle error
      throw new Error(result.error);
    }
  } catch (error) {
    console.error('Failed to request OTP:', error);
    throw error;
  }
};
```

### Step 2: Verify OTP

```javascript
const verifyOTP = async (sessionId, otpCode) => {
  try {
    const response = await fetch(
      'https://auth.iheal.digital/realms/iHeal/phone-auth/verify-otp',
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          sessionId: sessionId,  // From step 1
          code: otpCode          // e.g., "521141"
        })
      }
    );

    const result = await response.json();
    
    if (result.success) {
      // Authentication successful
      return {
        accessToken: result.accessToken,    // Use for API calls
        refreshToken: result.refreshToken,  // Use to get new access token
        expiresIn: result.expiresIn        // Seconds until token expires
      };
    } else {
      // Handle error (invalid OTP, session expired, etc.)
      throw new Error(result.error);
    }
  } catch (error) {
    console.error('Failed to verify OTP:', error);
    throw error;
  }
};
```

### Step 3: Resend OTP (Optional)

```javascript
const resendOTP = async (sessionId) => {
  try {
    const response = await fetch(
      'https://auth.iheal.digital/realms/iHeal/phone-auth/resend-otp',
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          sessionId: sessionId
        })
      }
    );

    const result = await response.json();
    
    if (result.success) {
      return {
        sessionId: result.sessionId,
        expiresIn: result.expiresIn
      };
    } else {
      throw new Error(result.error);
    }
  } catch (error) {
    console.error('Failed to resend OTP:', error);
    throw error;
  }
};
```

---

## 📱 Complete React Native Example

```javascript
import React, { useState } from 'react';
import { View, TextInput, Button, Text, Alert } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';

const KEYCLOAK_URL = 'https://auth.iheal.digital';
const REALM = 'iHeal';
const CLIENT_ID = 'iHealMobileApp';

const PhoneAuthScreen = () => {
  const [phoneNumber, setPhoneNumber] = useState('');
  const [otp, setOtp] = useState('');
  const [sessionId, setSessionId] = useState(null);
  const [step, setStep] = useState(1); // 1: Enter phone, 2: Enter OTP

  // Step 1: Request OTP
  const handleRequestOTP = async () => {
    try {
      const response = await fetch(
        `${KEYCLOAK_URL}/realms/${REALM}/phone-auth/request-otp`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            phone: phoneNumber,
            regionPrefix: '+91',
            clientId: CLIENT_ID
          })
        }
      );

      const result = await response.json();

      if (result.success) {
        setSessionId(result.sessionId);
        setStep(2);
        Alert.alert('Success', `OTP sent to ${result.phoneNumber}`);
      } else {
        Alert.alert('Error', result.error || 'Failed to send OTP');
      }
    } catch (error) {
      Alert.alert('Error', 'Network error. Please try again.');
      console.error(error);
    }
  };

  // Step 2: Verify OTP
  const handleVerifyOTP = async () => {
    try {
      const response = await fetch(
        `${KEYCLOAK_URL}/realms/${REALM}/phone-auth/verify-otp`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            sessionId: sessionId,
            code: otp
          })
        }
      );

      const result = await response.json();

      if (result.success) {
        // Save tokens
        await AsyncStorage.setItem('accessToken', result.accessToken);
        await AsyncStorage.setItem('refreshToken', result.refreshToken);
        
        Alert.alert('Success', 'Login successful!');
        // Navigate to home screen
      } else {
        Alert.alert(
          'Error', 
          result.error || 'Invalid OTP',
          result.remainingAttempts && [{
            text: 'OK',
            onPress: () => console.log(`${result.remainingAttempts} attempts remaining`)
          }]
        );
      }
    } catch (error) {
      Alert.alert('Error', 'Network error. Please try again.');
      console.error(error);
    }
  };

  // Resend OTP
  const handleResendOTP = async () => {
    try {
      const response = await fetch(
        `${KEYCLOAK_URL}/realms/${REALM}/phone-auth/resend-otp`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ sessionId })
        }
      );

      const result = await response.json();

      if (result.success) {
        Alert.alert('Success', 'OTP resent successfully');
      } else {
        Alert.alert('Error', result.error || 'Failed to resend OTP');
      }
    } catch (error) {
      Alert.alert('Error', 'Network error. Please try again.');
      console.error(error);
    }
  };

  return (
    <View style={{ padding: 20 }}>
      {step === 1 ? (
        <>
          <Text>Enter your phone number (India +91):</Text>
          <TextInput
            style={{ borderWidth: 1, padding: 10, marginVertical: 10 }}
            placeholder="7207207260"
            keyboardType="phone-pad"
            value={phoneNumber}
            onChangeText={setPhoneNumber}
          />
          <Button title="Send OTP" onPress={handleRequestOTP} />
        </>
      ) : (
        <>
          <Text>Enter OTP sent to {phoneNumber}:</Text>
          <TextInput
            style={{ borderWidth: 1, padding: 10, marginVertical: 10 }}
            placeholder="123456"
            keyboardType="number-pad"
            maxLength={6}
            value={otp}
            onChangeText={setOtp}
          />
          <Button title="Verify OTP" onPress={handleVerifyOTP} />
          <Button 
            title="Resend OTP" 
            onPress={handleResendOTP}
            color="#888"
          />
          <Button 
            title="Change Number" 
            onPress={() => {
              setStep(1);
              setOtp('');
            }}
            color="#ccc"
          />
        </>
      )}
    </View>
  );
};

export default PhoneAuthScreen;
```

---

## 🌍 Country Codes

### For India (Your Use Case)
```javascript
{
  phone: "7207207260",      // 10 digits, no leading zero
  regionPrefix: "+91"       // India country code
}
```

### For Other Countries
```javascript
// United States
{ phone: "4155552671", regionPrefix: "+1" }

// United Kingdom
{ phone: "2079460958", regionPrefix: "+44" }

// Get list of supported countries
fetch('https://auth.iheal.digital/realms/iHeal/phone-auth/countries')
  .then(r => r.json())
  .then(countries => {
    // [{ label: "India", code: "+91" }, ...]
  });
```

---

## 🔐 Using Access Tokens

After successful authentication, use the access token for API calls:

```javascript
// Save tokens after login
await AsyncStorage.setItem('accessToken', result.accessToken);
await AsyncStorage.setItem('refreshToken', result.refreshToken);

// Make authenticated API calls
const makeAuthenticatedRequest = async (url) => {
  const token = await AsyncStorage.getItem('accessToken');
  
  const response = await fetch(url, {
    method: 'GET',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json'
    }
  });
  
  if (response.status === 401) {
    // Token expired, refresh it
    await refreshAccessToken();
    return makeAuthenticatedRequest(url); // Retry
  }
  
  return response.json();
};

// Refresh token when access token expires
const refreshAccessToken = async () => {
  const refreshToken = await AsyncStorage.getItem('refreshToken');
  
  const response = await fetch(
    `${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded'
      },
      body: new URLSearchParams({
        grant_type: 'refresh_token',
        refresh_token: refreshToken,
        client_id: CLIENT_ID
      })
    }
  );
  
  const result = await response.json();
  
  if (result.access_token) {
    await AsyncStorage.setItem('accessToken', result.access_token);
    await AsyncStorage.setItem('refreshToken', result.refresh_token);
  } else {
    // Refresh failed, user needs to login again
    await AsyncStorage.clear();
    // Navigate to login screen
  }
};
```

---

## ⚠️ Error Handling

### Common Errors

| Error Code | Meaning | Action |
|------------|---------|--------|
| `INVALID_REQUEST` | Bad request format | Check JSON structure |
| `PHONE_REQUIRED` | Phone number missing | Add phone number |
| `INVALID_PHONE_FORMAT` | Invalid phone format | Check country code |
| `RATE_LIMIT_EXCEEDED` | Too many requests | Wait 1 minute |
| `SMS_SEND_FAILED` | SMS delivery failed | Retry or check phone |
| `SESSION_NOT_FOUND` | Invalid session | Request new OTP |
| `SESSION_EXPIRED` | Session timed out (10min) | Request new OTP |
| `INVALID_OTP` | Wrong OTP code | Re-enter correct OTP |
| `SESSION_LOCKED` | Too many failed attempts | Wait and request new OTP |

### Error Handling Example

```javascript
const handleError = (error) => {
  switch (error.errorCode) {
    case 'RATE_LIMIT_EXCEEDED':
      Alert.alert(
        'Too Many Attempts',
        'Please wait 1 minute before trying again.'
      );
      break;
      
    case 'INVALID_PHONE_FORMAT':
      Alert.alert(
        'Invalid Phone Number',
        'Please enter a valid 10-digit phone number.'
      );
      break;
      
    case 'INVALID_OTP':
      Alert.alert(
        'Invalid OTP',
        `Wrong code. ${error.remainingAttempts} attempts remaining.`
      );
      break;
      
    case 'SESSION_EXPIRED':
      Alert.alert(
        'Session Expired',
        'Your session has expired. Please request a new OTP.',
        [{ text: 'OK', onPress: () => setStep(1) }]
      );
      break;
      
    case 'SESSION_LOCKED':
      Alert.alert(
        'Account Locked',
        'Too many failed attempts. Please try again in a few minutes.',
        [{ text: 'OK', onPress: () => setStep(1) }]
      );
      break;
      
    default:
      Alert.alert('Error', error.error || 'Something went wrong');
  }
};

// Use in your API calls
try {
  const result = await verifyOTP(sessionId, otp);
  // Success
} catch (error) {
  handleError(error);
}
```

---

## ⏱️ Session Management

- **Session Duration**: 10 minutes
- **Max OTP Attempts**: 5 attempts per session
- **Max Resends**: 3 resends per session
- **Rate Limiting**: 3 OTP requests per phone per minute

### Timer Implementation

```javascript
import { useState, useEffect } from 'react';

const OTPTimer = ({ expiresIn, onExpired }) => {
  const [timeLeft, setTimeLeft] = useState(expiresIn);

  useEffect(() => {
    if (timeLeft <= 0) {
      onExpired();
      return;
    }

    const timer = setTimeout(() => {
      setTimeLeft(timeLeft - 1);
    }, 1000);

    return () => clearTimeout(timer);
  }, [timeLeft]);

  const minutes = Math.floor(timeLeft / 60);
  const seconds = timeLeft % 60;

  return (
    <Text>
      Time remaining: {minutes}:{seconds.toString().padStart(2, '0')}
    </Text>
  );
};

// Usage
<OTPTimer 
  expiresIn={600} // 10 minutes
  onExpired={() => Alert.alert('OTP Expired', 'Please request a new OTP')}
/>
```

---

## 🧪 Testing

### Test with cURL

```bash
# 1. Request OTP
curl -X POST https://auth.iheal.digital/realms/iHeal/phone-auth/request-otp \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "7207207260",
    "regionPrefix": "+91",
    "clientId": "iHealMobileApp"
  }'

# Response:
# {
#   "success": true,
#   "sessionId": "550e8400-e29b-41d4-a716-446655440000",
#   "phoneNumber": "+917207207260",
#   "expiresIn": 600
# }

# 2. Verify OTP (check SMS for code)
curl -X POST https://auth.iheal.digital/realms/iHeal/phone-auth/verify-otp \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "550e8400-e29b-41d4-a716-446655440000",
    "code": "123456"
  }'

# Response:
# {
#   "success": true,
#   "accessToken": "eyJhbGci...",
#   "refreshToken": "eyJhbGci...",
#   "expiresIn": 300
# }
```

---

## 🚀 Deployment Checklist

### Server Side
- [ ] Deploy updated Keycloak plugin
- [ ] Verify SMS service is configured
- [ ] Test API endpoints are accessible
- [ ] Check Keycloak client is configured (iHealMobileApp)

### Mobile App
- [ ] Update Keycloak URL to production
- [ ] Use correct realm name (iHeal)
- [ ] Use correct client ID (iHealMobileApp)
- [ ] Test phone number input validation
- [ ] Test OTP request flow
- [ ] Test OTP verification flow
- [ ] Test error handling
- [ ] Test token refresh logic

---

## 📞 Support

If you have issues:

1. **Check API Response**: Log the full response from each endpoint
2. **Check Phone Format**: Must be 10 digits for India, no leading zero
3. **Check Country Code**: Must be +91 for India
4. **Check Session ID**: Save and pass sessionId correctly
5. **Check Network**: Use HTTPS, not HTTP

**Contact:**
- Email: dev@ssegning.com
- GitHub: https://github.com/vymalo/keycloak-phone-number

---

## ✅ Quick Checklist

For India (+91):

```javascript
// ✅ CORRECT
{
  phone: "7207207260",
  regionPrefix: "+91",
  code: "123456"
}

// ❌ WRONG
{
  phone: "07207207260",  // Don't include leading zero
  regionPrefix: "+917",   // Wrong country code
  tan: "123456"          // Wrong field name
}
```

That's it! Your mobile app will now work with direct API calls, no browser needed! 🎉
