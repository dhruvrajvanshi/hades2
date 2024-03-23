"use strict";
exports.__esModule = true;
exports.deactivate = exports.activate = void 0;
var client;
function activate(context) {
}
exports.activate = activate;
function deactivate() {
    if (!client) {
        return undefined;
    }
    return client.stop();
}
exports.deactivate = deactivate;
