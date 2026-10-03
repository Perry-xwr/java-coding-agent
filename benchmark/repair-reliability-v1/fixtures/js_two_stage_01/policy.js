function remaining(limit, used) {
    return Math.max(0, limit - used);
}

const example = remaining(10, 3);
module.exports = { remaining, example };
