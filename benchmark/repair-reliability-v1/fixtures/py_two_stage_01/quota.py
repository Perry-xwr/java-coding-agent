def remaining(limit, used):
    return max(0, limit - used)


if __name__ == "__main__":
    print(remaining(10, 3))
