"""실험 정의·배정기 계약 — 해시 결정론, 균등 분할, sample 미만 미편입, 비활성 실험, 실험 간 독립 해시."""

from pathlib import Path

import pytest

from app.experiments.assigner import ExperimentAssigner
from app.experiments.definition import ExperimentDefinition, VariantSpec, load_experiments
from app.supervisor.state import ExperimentAssignment


def _experiment(name: str = "analysis-prompt-v2", sample: float = 1.0, status: str = "active") -> ExperimentDefinition:
    return ExperimentDefinition(
        name=name,
        target="analysis",
        status=status,
        sample=sample,
        variants={"A": VariantSpec(prompt="v1"), "B": VariantSpec(prompt="v2")},
    )


def test_assignment_is_deterministic_for_same_incident():
    assigner = ExperimentAssigner([_experiment()])
    first = assigner.assign("inc-latency-surge-20260915010203-abc123")
    second = assigner.assign("inc-latency-surge-20260915010203-abc123")
    assert first == second
    assert isinstance(first, ExperimentAssignment)
    assert first.name == "analysis-prompt-v2"
    assert first.variant in {"A", "B"}


def test_two_variants_split_close_to_half():
    assigner = ExperimentAssigner([_experiment()])
    variants = [assigner.assign(f"inc-{i}").variant for i in range(2000)]
    share_b = variants.count("B") / len(variants)
    assert 0.45 <= share_b <= 0.55, share_b


def test_sample_below_one_leaves_incidents_out_of_the_experiment():
    assigner = ExperimentAssigner([_experiment(sample=0.5)])
    assigned = [assigner.assign(f"inc-{i}") for i in range(2000)]
    share_in = sum(a is not None for a in assigned) / len(assigned)
    assert 0.45 <= share_in <= 0.55, share_in
    # 편입된 인시던트 안에서는 여전히 반반
    in_experiment = [a.variant for a in assigned if a is not None]
    assert 0.4 <= in_experiment.count("B") / len(in_experiment) <= 0.6


def test_sample_zero_or_concluded_assigns_nothing():
    assert ExperimentAssigner([_experiment(sample=0.0)]).assign("inc-1") is None
    assert ExperimentAssigner([_experiment(status="concluded")]).assign("inc-1") is None
    assert ExperimentAssigner([]).assign("inc-1") is None


def test_experiments_hash_independently_by_name():
    a = ExperimentAssigner([_experiment(name="exp-a")])
    b = ExperimentAssigner([_experiment(name="exp-b")])
    ids = [f"inc-{i}" for i in range(500)]
    agree = sum(a.assign(i).variant == b.assign(i).variant for i in ids) / len(ids)
    # 같은 해시를 쓰면 1.0 — 이름이 salt 여야 두 실험의 배정이 서로 얽히지 않는다
    assert 0.35 <= agree <= 0.65, agree


def test_assignment_resolves_variant_spec():
    definition = ExperimentDefinition(
        name="analysis-model-haiku",
        target="analysis",
        status="active",
        sample=1.0,
        variants={"A": VariantSpec(), "B": VariantSpec(model_override=True)},
    )
    assigner = ExperimentAssigner([definition])
    assignments = {assigner.assign(f"inc-{i}").variant: assigner.assign(f"inc-{i}") for i in range(50)}
    assert assignments["A"].prompt_version is None and assignments["A"].model_override is False
    assert assignments["B"].prompt_version is None and assignments["B"].model_override is True
    assert assignments["B"].gateway_variant == "analysis-model-haiku:B"
    assert assignments["A"].gateway_variant is None


def test_only_first_active_experiment_assigns():
    assigner = ExperimentAssigner([_experiment(name="first", status="concluded"), _experiment(name="second"), _experiment(name="third")])
    assert assigner.assign("inc-1").name == "second"


def test_load_experiments_reads_yaml_and_rejects_bad_definitions(tmp_path: Path):
    good = tmp_path / "experiments.yml"
    good.write_text(
        "experiments:\n"
        "  - name: analysis-prompt-v2\n"
        "    target: analysis\n"
        "    status: active\n"
        "    sample: 1.0\n"
        "    variants:\n"
        "      A: {prompt: v1}\n"
        "      B: {prompt: v2}\n",
        encoding="utf-8",
    )
    [definition] = load_experiments(good)
    assert definition.name == "analysis-prompt-v2"
    assert definition.variants["B"].prompt == "v2"
    assert definition.control == "A"

    single = tmp_path / "single.yml"
    single.write_text("experiments:\n  - name: x\n    target: analysis\n    variants:\n      A: {prompt: v1}\n", encoding="utf-8")
    with pytest.raises(ValueError):
        load_experiments(single)

    bad_target = tmp_path / "target.yml"
    bad_target.write_text(
        "experiments:\n  - name: x\n    target: monitor\n    variants:\n      A: {}\n      B: {prompt: v2}\n", encoding="utf-8"
    )
    with pytest.raises(ValueError):
        load_experiments(bad_target)


def test_packaged_experiments_file_loads():
    from app.experiments.definition import EXPERIMENTS_FILE

    definitions = load_experiments(EXPERIMENTS_FILE)
    names = {d.name for d in definitions}
    assert "analysis-prompt-v2" in names
