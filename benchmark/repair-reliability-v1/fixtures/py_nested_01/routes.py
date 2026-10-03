def route(response, transient, attempts):
    if response is None:
        if transient:
            return "retry"
        else:
            return "empty"
    return response
