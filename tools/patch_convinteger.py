"""Patch unsupported ConvInteger nodes in the downloaded Indic ONNX model."""

import argparse
import shutil
from pathlib import Path

import onnx
from onnx import helper, numpy_helper


def patch(source: Path, destination: Path) -> int:
    model = onnx.load(source, load_external_data=False)
    nodes = list(model.graph.node)
    conv_integer_count = sum(node.op_type == "ConvInteger" for node in nodes)
    producers = {output: node for node in nodes for output in node.output}
    initializers = {item.name: item for item in model.graph.initializer}
    consumers = {}
    for node in nodes:
        for item in node.input:
            consumers.setdefault(item, []).append(node)

    replacements = {}
    removed = set()
    added = []

    for node in nodes:
        if node.op_type != "ConvInteger":
            continue

        activation_q, weight_q = node.input[:2]
        quantizer = producers[activation_q]
        assert quantizer.op_type == "DynamicQuantizeLinear"
        activation_scale, activation_zp = quantizer.output[1:]

        weight_prefix = weight_q.removesuffix("_quantized")
        weight_scale = weight_prefix + "_scale"
        weight_zp = weight_prefix + "_zero_point"
        assert weight_q in initializers and weight_scale in initializers and weight_zp in initializers

        activation_f = node.name + "_activation_float"
        weight_f = node.name + "_weight_float"
        added.extend(
            [
                helper.make_node(
                    "DequantizeLinear",
                    [activation_q, activation_scale, activation_zp],
                    [activation_f],
                    name=node.name + "_activation_dequantize",
                ),
                helper.make_node(
                    "DequantizeLinear",
                    [weight_q, weight_scale, weight_zp],
                    [weight_f],
                    name=node.name + "_weight_dequantize",
                    axis=0,
                ),
            ]
        )

        cast = consumers[node.output[0]]
        assert len(cast) == 1 and cast[0].op_type == "Cast"
        scale_mul = consumers[cast[0].output[0]]
        assert len(scale_mul) == 1 and scale_mul[0].op_type == "Mul"
        scaled_output = scale_mul[0].output[0]
        replacements[scaled_output] = node.output[0]
        removed.update({cast[0].name, scale_mul[0].name})

        new_conv = onnx.NodeProto()
        new_conv.CopyFrom(node)
        new_conv.op_type = "Conv"
        del new_conv.input[:]
        new_conv.input.extend([activation_f, weight_f])
        added.append(new_conv)
        removed.add(node.name)

    assert len(removed) == conv_integer_count * 3
    rewritten = []
    additions_by_name = {node.name: node for node in added}
    inserted = set()
    for node in nodes:
        if node.name in removed:
            if node.op_type == "ConvInteger":
                rewritten.extend(
                    additions_by_name[name]
                    for name in (node.name + "_activation_dequantize", node.name + "_weight_dequantize", node.name)
                )
            continue
        for index, value in enumerate(node.input):
            node.input[index] = replacements.get(value, value)
        rewritten.append(node)

    del model.graph.node[:]
    model.graph.node.extend(rewritten)
    destination.parent.mkdir(parents=True, exist_ok=True)
    candidate = destination.with_suffix(".tmp.onnx")
    onnx.save(model, candidate)
    onnx.checker.check_model(str(candidate))
    candidate.replace(destination)
    return conv_integer_count


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    count = patch(args.source, args.destination)
    print(f"patched {count} ConvInteger nodes")
