"""Add per-token pitch and duration controls to the shipped FastPitch ONNX graph."""

import argparse
from pathlib import Path

import onnx
from onnx import TensorProto, helper, numpy_helper
import numpy as np


PITCH_TENSOR = "/pitch_predictor/Mul_3_output_0"
PITCH_CONSUMER = "/pitch_emb/Conv"
DURATION_TENSOR = "/Mul_2_output_0_DequantizeLinear_Output"
DURATION_CONSUMER = "/Round_1"


def only_node(nodes, name):
    matches = [node for node in nodes if node.name == name]
    if len(matches) != 1:
        raise RuntimeError(f"Expected one {name!r} node, found {len(matches)}")
    return matches[0]


def patch_model(model):
    existing_inputs = {value.name for value in model.graph.input}
    if {"pitch_bias", "duration_scale"} & existing_inputs:
        raise RuntimeError("Graph already has prosody inputs")

    pitch_consumer = only_node(model.graph.node, PITCH_CONSUMER)
    duration_consumer = only_node(model.graph.node, DURATION_CONSUMER)
    if list(pitch_consumer.input).count(PITCH_TENSOR) != 1:
        raise RuntimeError(f"{PITCH_CONSUMER} no longer consumes {PITCH_TENSOR}")
    if list(duration_consumer.input).count(DURATION_TENSOR) != 1:
        raise RuntimeError(f"{DURATION_CONSUMER} no longer consumes {DURATION_TENSOR}")

    model.graph.input.extend(
        [
            helper.make_tensor_value_info("pitch_bias", TensorProto.FLOAT, ["B", "T_text"]),
            helper.make_tensor_value_info("duration_scale", TensorProto.FLOAT, ["B", "T_text"]),
        ]
    )
    model.graph.initializer.append(
        numpy_helper.from_array(np.array([1], dtype=np.int64), "prosody_pitch_bias_axes")
    )

    pitch_bias_3d = "/prosody/pitch_bias_3d"
    pitch_output = "/prosody/pitch_output"
    duration_output = "/prosody/duration_output"
    pitch_nodes = [
        helper.make_node(
            "Unsqueeze",
            ["pitch_bias", "prosody_pitch_bias_axes"],
            [pitch_bias_3d],
            name="/prosody/UnsqueezePitchBias",
        ),
        helper.make_node(
            "Add",
            [PITCH_TENSOR, pitch_bias_3d],
            [pitch_output],
            name="/prosody/AddPitchBias",
        ),
    ]
    duration_node = helper.make_node(
        "Mul",
        [DURATION_TENSOR, "duration_scale"],
        [duration_output],
        name="/prosody/ScaleDuration",
    )

    pitch_consumer.input[0] = pitch_output
    duration_consumer.input[0] = duration_output
    rewritten = []
    for node in model.graph.node:
        if node is pitch_consumer:
            rewritten.extend(pitch_nodes)
        if node is duration_consumer:
            rewritten.append(duration_node)
        rewritten.append(node)
    del model.graph.node[:]
    model.graph.node.extend(rewritten)
    model.graph.doc_string += (
        "\nProsody inputs added by scripts/patch_fastpitch_prosody_onnx.py: "
        "pitch_bias FLOAT[B,T_text] and duration_scale FLOAT[B,T_text]."
    )
    onnx.checker.check_model(model)
    return model


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--input",
        type=Path,
        default=Path("models/tts/en-hi/fastpitch_en+hi.qdq-safe.onnx"),
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("models/tts/en-hi/fastpitch_en+hi.prosody.onnx"),
    )
    args = parser.parse_args()
    model = patch_model(onnx.load(args.input))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(model, args.output)
    print(args.output)
    print("pitch:", PITCH_TENSOR, "FLOAT[B,1,T_text] -> /prosody/pitch_output")
    print("duration:", DURATION_TENSOR, "FLOAT[B,T_text] -> /prosody/duration_output")


if __name__ == "__main__":
    main()
