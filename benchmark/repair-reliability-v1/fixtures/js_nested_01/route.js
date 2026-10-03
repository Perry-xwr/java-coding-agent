function route(approved) {
    if (approved) {
        return "accepted";
    } else {
        return "denied";
    }
}

module.exports = { route };
