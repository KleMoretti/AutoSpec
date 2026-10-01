"""Small, deterministic uncertainty helpers for the P2 evaluation gate.

The functions in this module operate on already collected case facts. They do
not turn missing, fixture, or self-reported data into evidence.
"""
from __future__ import annotations

import math
import random
from collections.abc import Callable, Sequence


STATISTICS_VERSION = "autospec-p2-statistics-v1"


def wilson_interval(successes: int, trials: int, *, confidence: float = 0.95) -> tuple[float, float]:
    """Return a two-sided Wilson interval for a binomial proportion."""

    if trials < 0 or successes < 0 or successes > trials:
        raise ValueError("successes/trials must satisfy 0 <= successes <= trials")
    if not 0 < confidence < 1:
        raise ValueError("confidence must be between 0 and 1")
    if trials == 0:
        return (float("nan"), float("nan"))
    z = 1.959963984540054 if math.isclose(confidence, 0.95) else _normal_quantile((1 + confidence) / 2)
    p = successes / trials
    denominator = 1 + z * z / trials
    centre = (p + z * z / (2 * trials)) / denominator
    radius = z * math.sqrt((p * (1 - p) + z * z / (4 * trials)) / trials) / denominator
    return (max(0.0, centre - radius), min(1.0, centre + radius))


def cluster_bootstrap(
    clusters: Sequence[Sequence[float]],
    statistic: Callable[[list[float]], float],
    *,
    seed: int,
    iterations: int = 2000,
    confidence: float = 0.95,
) -> tuple[float, float]:
    """Bootstrap by case cluster, never by individual repetition."""

    if not clusters or any(not cluster for cluster in clusters):
        return (float("nan"), float("nan"))
    if iterations < 100:
        raise ValueError("cluster bootstrap requires at least 100 iterations")
    if not 0 < confidence < 1:
        raise ValueError("confidence must be between 0 and 1")
    rng = random.Random(seed)
    samples = []
    for _ in range(iterations):
        picked = [clusters[rng.randrange(len(clusters))] for _ in clusters]
        samples.append(statistic([value for cluster in picked for value in cluster]))
    samples.sort()
    alpha = (1 - confidence) / 2
    return (percentile(samples, alpha), percentile(samples, 1 - alpha))


def paired_cluster_bootstrap_difference(
    baseline: dict[str, Sequence[float]],
    candidate: dict[str, Sequence[float]],
    *,
    seed: int,
    iterations: int = 2000,
    confidence: float = 0.95,
) -> dict[str, float | int | str]:
    """Return a fixed-seed candidate-minus-baseline clustered interval."""

    case_ids = sorted(set(baseline) & set(candidate))
    if not case_ids:
        return {"estimate": float("nan"), "lower": float("nan"), "upper": float("nan"),
                "sample_count": 0, "seed": seed, "statistics_version": STATISTICS_VERSION}
    if any(not baseline[key] or not candidate[key] for key in case_ids):
        raise ValueError("paired bootstrap cases must contain repetitions")
    rng = random.Random(seed)
    differences = [
        (sum(candidate[key]) / len(candidate[key])) - (sum(baseline[key]) / len(baseline[key]))
        for key in case_ids
    ]
    estimates = []
    for _ in range(iterations):
        picked = [differences[rng.randrange(len(differences))] for _ in differences]
        estimates.append(sum(picked) / len(picked))
    estimates.sort()
    alpha = (1 - confidence) / 2
    return {
        "estimate": sum(differences) / len(differences),
        "lower": percentile(estimates, alpha),
        "upper": percentile(estimates, 1 - alpha),
        "sample_count": len(case_ids),
        "seed": seed,
        "statistics_version": STATISTICS_VERSION,
    }


def percentile(values: Sequence[float], quantile: float) -> float:
    if not values:
        return float("nan")
    position = (len(values) - 1) * quantile
    low, high = math.floor(position), math.ceil(position)
    return values[low] + (values[high] - values[low]) * (position - low)


def _normal_quantile(probability: float) -> float:
    # The common 95% path above is exact. This fallback avoids a SciPy
    # dependency for callers that predeclare another confidence level.
    if not 0 < probability < 1:
        raise ValueError("probability must be between 0 and 1")
    if probability < 0.5:
        return -_normal_quantile(1 - probability)
    q = probability - 0.5
    numerator = q * (2.50662827745924 + q * (-30.6647980661472 + q * (138.357751867269 + q * (-275.928510446969 + q * (220.946098424521 + q * -39.6968302866538)))))
    denominator = 1 + q * (-13.2806815528857 + q * (66.8013118877197 + q * (-155.698979859887 + q * (161.585836858041 + q * -54.4760987982241))))
    return numerator / denominator
