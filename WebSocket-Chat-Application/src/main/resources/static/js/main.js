'use strict';

var usernamePage = document.querySelector('#username-page');
var chatPage = document.querySelector('#chat-page');
var usernameForm = document.querySelector('#usernameForm');
var usernameInput = document.querySelector('#name');
var passwordInput = document.querySelector('#password');
var registerButton = document.querySelector('#register');
var signInButton = document.querySelector('#usernameForm button[type="submit"]');
var authError = document.querySelector('#auth-error');
var messageForm = document.querySelector('#messageForm');
var messageInput = document.querySelector('#message');
var messageArea = document.querySelector('#messageArea');
var connectingElement = document.querySelector('.connecting');
var sendButton = document.querySelector('#messageForm button[type="submit"]');

var stompClient = null;
// The access token lives only in memory (not localStorage), so it is gone when the tab closes.
var accessToken = null;
var authState = 'signed_out';
var generation = 0;
var activeAttempt = null;

var colors = [
    '#2196F3', '#32c787', '#00BCD4', '#ff5652',
    '#ffc107', '#ff85af', '#FF9800', '#39bbb0'
];

function setAuthState(state) {
    authState = state;
    signInButton.disabled = state !== 'signed_out';
    registerButton.disabled = state !== 'signed_out';
    sendButton.disabled = state !== 'connected';
}

function showAuthError(text) {
    authError.textContent = text;
    authError.classList.remove('hidden');
}

function clearAuthError() {
    authError.textContent = '';
    authError.classList.add('hidden');
}

function readCredentials(createAccount) {
    var credentials = {
        username: usernameInput.value.trim(),
        password: passwordInput.value
    };
    if (!credentials.username || !credentials.password) {
        showAuthError('Enter a username and password.');
        return null;
    }
    if (createAccount &&
        (credentials.password.length < 8 || new TextEncoder().encode(credentials.password).length > 72)) {
        showAuthError('Password must be at least 8 characters and at most 72 UTF-8 bytes.');
        return null;
    }
    return credentials;
}

function postJson(url, body) {
    return fetch(url, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify(body)
    }).then(function (response) {
        return response.json()
            .catch(function () { return {}; })
            .then(function (data) { return {ok: response.ok, data: data}; });
    });
}

// Turns a problem-details response into a readable message.
function describeError(result, fallback) {
    var data = result.data || {};
    if (data.errors) {
        return Object.keys(data.errors).map(function (field) {
            return field + ' ' + data.errors[field];
        }).join('. ');
    }
    return data.detail || fallback;
}

function isCurrent(attempt, client) {
    return activeAttempt === attempt && attempt.generation === generation &&
        (!client || attempt.client === client);
}

function disposeClient(client, socket) {
    try {
        if (client) {
            client.disconnect();
        }
    } catch (error) {
    } finally {
        if (socket && socket.readyState !== 3) {
            socket.close();
        }
    }
}

function endAttempt(attempt, message) {
    if (!isCurrent(attempt)) {
        return;
    }
    activeAttempt = null;
    accessToken = null;
    stompClient = null;
    setAuthState('signed_out');
    usernamePage.classList.remove('hidden');
    chatPage.classList.add('hidden');
    showAuthError(message);
    disposeClient(attempt.client, attempt.socket);
}

function signIn(credentials, attempt) {
    return postJson('/api/auth/login', credentials).then(function (result) {
        if (!isCurrent(attempt)) {
            return;
        }
        if (!result.ok) {
            endAttempt(attempt, describeError(result, 'Sign-in failed.'));
            return;
        }
        if (!result.data || typeof result.data.accessToken !== 'string' || !result.data.accessToken.trim()) {
            endAttempt(attempt, 'Sign-in failed. Please try again.');
            return;
        }
        accessToken = result.data.accessToken;
        passwordInput.value = '';
        connect(attempt);
    });
}

function login(event) {
    event.preventDefault();
    authenticate(false);
}

function register(event) {
    event.preventDefault();
    authenticate(true);
}

function authenticate(createAccount) {
    if (authState !== 'signed_out') {
        return;
    }
    clearAuthError();
    var credentials = readCredentials(createAccount);
    if (!credentials) {
        return;
    }
    setAuthState('authenticating');
    var attempt = {generation: ++generation, client: null, socket: null};
    activeAttempt = attempt;
    var request = createAccount
        ? postJson('/api/auth/register', credentials).then(function (result) {
            if (!isCurrent(attempt)) {
                return;
            }
            if (!result.ok) {
                endAttempt(attempt, describeError(result, 'Could not create the account.'));
                return;
            }
            return signIn(credentials, attempt);
        })
        : signIn(credentials, attempt);
    request.catch(function () {
        endAttempt(attempt, 'Could not reach the server. Please sign in again.');
    });
}

function connect(attempt) {
    setAuthState('connecting');
    usernamePage.classList.add('hidden');
    chatPage.classList.remove('hidden');

    connectingElement.textContent = 'Connecting...';
    connectingElement.classList.remove('hidden');
    var socket = attempt.socket = new SockJS('/ws');
    var client = attempt.client = Stomp.over(socket);
    stompClient = client;
    // stomp.js logs every frame by default, including the Authorization header of CONNECT.
    client.debug = null;

    // Browsers can't set headers on the WebSocket handshake, so the token rides on the STOMP CONNECT frame.
    client.connect({Authorization: 'Bearer ' + accessToken}, function () {
        if (!isCurrent(attempt, client)) {
            disposeClient(client, socket);
            return;
        }
        if (authState !== 'connecting') {
            return;
        }
        try {
            client.subscribe('/topic/public', function (payload) {
                if (isCurrent(attempt, client) && authState === 'connected') {
                    onMessageReceived(payload);
                }
            });
            client.send('/app/chat.addUser', {}, JSON.stringify({}));
            setAuthState('connected');
            connectingElement.classList.add('hidden');
        } catch (error) {
            endAttempt(attempt, 'Connection lost. Please sign in again.');
        }
    }, function (error) {
        if (!isCurrent(attempt, client)) {
            return;
        }
        var rejected = error && error.headers && error.headers.message === 'Unauthorized';
        endAttempt(attempt, rejected
            ? 'Your session has expired. Please sign in again.'
            : 'Connection lost. Please sign in again.');
    });
}


function sendMessage(event) {
    event.preventDefault();
    var attempt = activeAttempt;
    var client = stompClient;
    if (authState !== 'connected' || !stompClient || !stompClient.connected) {
        return;
    }
    var messageContent = messageInput.value.trim();
    if (messageContent) {
        // No "sender" field: the server stamps the authenticated username on every message
        var chatMessage = {
            content: messageContent,
            type: 'CHAT'
        };
        try {
            client.send('/app/chat.sendMessage', {}, JSON.stringify(chatMessage));
            if (isCurrent(attempt, client)) {
                messageInput.value = '';
            }
        } catch (error) {
            endAttempt(attempt, 'Connection lost. Please sign in again.');
        }
    }
}


function onMessageReceived(payload) {
    var message = JSON.parse(payload.body);

    var messageElement = document.createElement('li');

    if(message.type === 'JOIN') {
        messageElement.classList.add('event-message');
        message.content = message.sender + ' joined!';
    } else if (message.type === 'LEAVE') {
        messageElement.classList.add('event-message');
        message.content = message.sender + ' left!';
    } else {
        messageElement.classList.add('chat-message');

        var avatarElement = document.createElement('i');
        var avatarText = document.createTextNode(message.sender[0]);
        avatarElement.appendChild(avatarText);
        avatarElement.style['background-color'] = getAvatarColor(message.sender);

        messageElement.appendChild(avatarElement);

        var usernameElement = document.createElement('span');
        var usernameText = document.createTextNode(message.sender);
        usernameElement.appendChild(usernameText);
        messageElement.appendChild(usernameElement);
    }

    // createTextNode (never innerHTML) keeps user content from being interpreted as HTML
    var textElement = document.createElement('p');
    var messageText = document.createTextNode(message.content);
    textElement.appendChild(messageText);

    messageElement.appendChild(textElement);

    messageArea.appendChild(messageElement);
    messageArea.scrollTop = messageArea.scrollHeight;
}


function getAvatarColor(messageSender) {
    var hash = 0;
    for (var i = 0; i < messageSender.length; i++) {
        hash = 31 * hash + messageSender.charCodeAt(i);
    }
    var index = Math.abs(hash % colors.length);
    return colors[index];
}

usernameForm.addEventListener('submit', login, true);
registerButton.addEventListener('click', register, true);
messageForm.addEventListener('submit', sendMessage, true);
setAuthState('signed_out');
