'use strict';

var usernamePage = document.querySelector('#username-page');
var chatPage = document.querySelector('#chat-page');
var usernameForm = document.querySelector('#usernameForm');
var usernameInput = document.querySelector('#name');
var passwordInput = document.querySelector('#password');
var registerButton = document.querySelector('#register');
var authError = document.querySelector('#auth-error');
var messageForm = document.querySelector('#messageForm');
var messageInput = document.querySelector('#message');
var messageArea = document.querySelector('#messageArea');
var connectingElement = document.querySelector('.connecting');

var stompClient = null;
// The access token lives only in memory (not localStorage), so it is gone when the tab closes.
var accessToken = null;

var colors = [
    '#2196F3', '#32c787', '#00BCD4', '#ff5652',
    '#ffc107', '#ff85af', '#FF9800', '#39bbb0'
];

function showAuthError(text) {
    authError.textContent = text;
    authError.classList.remove('hidden');
}

function clearAuthError() {
    authError.textContent = '';
    authError.classList.add('hidden');
}

function readCredentials() {
    var credentials = {
        username: usernameInput.value.trim(),
        password: passwordInput.value
    };
    if (!credentials.username || !credentials.password) {
        showAuthError('Enter a username and password.');
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

function signIn(credentials) {
    return postJson('/api/auth/login', credentials).then(function (result) {
        if (!result.ok) {
            showAuthError(describeError(result, 'Sign-in failed.'));
            return;
        }
        accessToken = result.data.accessToken;
        passwordInput.value = '';
        connect();
    });
}

function login(event) {
    event.preventDefault();
    clearAuthError();
    var credentials = readCredentials();
    if (credentials) {
        signIn(credentials).catch(function () { showAuthError('Could not reach the server.'); });
    }
}

function register() {
    clearAuthError();
    var credentials = readCredentials();
    if (!credentials) {
        return;
    }
    postJson('/api/auth/register', credentials).then(function (result) {
        if (!result.ok) {
            showAuthError(describeError(result, 'Could not create the account.'));
            return;
        }
        return signIn(credentials);
    }).catch(function () { showAuthError('Could not reach the server.'); });
}

function connect() {
    usernamePage.classList.add('hidden');
    chatPage.classList.remove('hidden');

    var socket = new SockJS('/ws');
    stompClient = Stomp.over(socket);
    // stomp.js logs every frame by default, including the Authorization header of CONNECT.
    stompClient.debug = null;

    // Browsers can't set headers on the WebSocket handshake, so the token rides on the STOMP CONNECT frame.
    stompClient.connect({Authorization: 'Bearer ' + accessToken}, onConnected, onError);
}


function onConnected() {
    // Subscribe to the Public Topic
    stompClient.subscribe('/topic/public', onMessageReceived);

    // Announce ourselves; the server takes the username from the verified token
    stompClient.send("/app/chat.addUser", {}, JSON.stringify({}));

    connectingElement.classList.add('hidden');
}


function onError(error) {
    var rejected = error && error.headers && error.headers.message === 'Unauthorized';
    connectingElement.textContent = rejected
        ? 'Your session has expired. Please refresh the page and sign in again.'
        : 'Could not connect to WebSocket server. Please refresh this page to try again!';
    connectingElement.style.color = 'red';
    connectingElement.classList.remove('hidden');
}


function sendMessage(event) {
    var messageContent = messageInput.value.trim();
    if(messageContent && stompClient) {
        // No "sender" field: the server stamps the authenticated username on every message
        var chatMessage = {
            content: messageContent,
            type: 'CHAT'
        };
        stompClient.send("/app/chat.sendMessage", {}, JSON.stringify(chatMessage));
        messageInput.value = '';
    }
    event.preventDefault();
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

usernameForm.addEventListener('submit', login, true)
registerButton.addEventListener('click', register, true)
messageForm.addEventListener('submit', sendMessage, true)
