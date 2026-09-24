def clamp_score(score):
    if score < 0:
        return 0
    if score > 100:
        return 100
    return score