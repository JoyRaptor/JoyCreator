"""Independent scalar design checks; no renderer or runtime acceptance claim."""
from math import isclose


def over(src, dst):
    return tuple(src[i] + dst[i] * (1 - src[3]) for i in range(4))


def multiply(src, dst):
    rgb = tuple(src[i] * dst[i] + src[i] * (1 - dst[3])
                + dst[i] * (1 - src[3]) for i in range(3))
    return rgb + (src[3] + dst[3] * (1 - src[3]),)


def erase(src, dst):
    return tuple(value * (1 - src[3]) for value in dst)


def equal(actual, expected):
    assert all(isclose(a, b, abs_tol=1e-12) for a, b in zip(actual, expected)), (actual, expected)


def different(a, b):
    assert any(abs(x - y) > 1e-9 for x, y in zip(a, b)), (a, b)


def main():
    clear = (0., 0., 0., 0.)
    white = (1., 1., 1., 1.)
    red = (.5, 0., 0., .5)
    blue = (0., 0., .5, .5)
    green = (0., .5, 0., .5)
    checks = 0
    # Two normal sources at distinct chronological positions.
    equal(over(blue, over(red, white)), (.5, .25, .75, 1.))
    equal(over(red, over(blue, white)), (.75, .25, .5, 1.))
    different(over(blue, over(red, white)), over(red, over(blue, white)))
    checks += 1
    # Moving a multiply contribution past later translucent normal paint.
    different(over(blue, multiply(red, white)), multiply(red, over(blue, white)))
    checks += 1
    # Moving a normal contribution across a later multiply event.
    different(multiply(green, over(red, white)), over(red, multiply(green, white)))
    checks += 1
    # Erase is chronological too: erasing later paint is not erasing its underlay.
    different(over(blue, erase(red, white)), erase(red, over(blue, white)))
    checks += 1
    # Exact one-hot reconstruction, including endpoints represented by either owner.
    for owner in range(3):
        assembled = clear
        for child in range(3):
            assembled = over(red if child == owner else clear, assembled)
        equal(assembled, red)
        for operator in (over, multiply, erase):
            equal(operator(assembled, over(blue, white)), operator(red, over(blue, white)))
    checks += 1
    # Once moved children overlap, independent multiply is a different policy.
    grouped = multiply(over(blue, red), white)
    separate = multiply(blue, multiply(red, white))
    different(grouped, separate)
    equal(grouped, (.5, .25, .75, 1.))
    equal(separate, (.5, .25, .5, 1.))
    checks += 1
    print(f"PASS: {checks} scalar design groups; no native/runtime acceptance")


if __name__ == "__main__":
    main()
