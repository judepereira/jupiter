import {configureChatStreamControls, activePrimaryPendingAssistantRow, requestStopActiveChat, replaceChatContainerFromHtml, updateChatSendButtonState} from './streams/control.js';
import {bindPendingStreams} from './streams/bind.js';

configureChatStreamControls({bindPendingStreams});

export {
    activePrimaryPendingAssistantRow,
    bindPendingStreams,
    requestStopActiveChat,
    replaceChatContainerFromHtml,
    updateChatSendButtonState
};
