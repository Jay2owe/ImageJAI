from __future__ import annotations

import pytest

from agent.gemma4_31b import safety, tools_fiji, tools_jobs


@pytest.mark.parametrize(
    "code, primitive",
    [
        ('exec("python", "-V");', "exec"),
        ('eval("script", "java.lang.Runtime.getRuntime()");', "eval"),
        ('call("java.lang.System.setProperty", "x", "y");', "call"),
        ('runMacro("/tmp/untrusted.ijm");', "runMacro"),
        ('runMacroFile("/tmp/untrusted.ijm");', "runMacro"),
        ('IJ.runMacro("print(1);");', "IJ.runMacro"),
        ('IJ.runMacroFile("/tmp/untrusted.ijm");', "IJ.runMacro"),
        ('Ext.install("/tmp/extension.jar");', "Ext.*"),
        ('run("Script...");', 'run("Script...")'),
        ('run("Groovy Script", "script=[println 1]");', "Groovy Script"),
        ('run("BeanShell Interpreter");', "BeanShell"),
        ('run("Compile and Run...");', "Compile and Run"),
        ('doCommand("JavaScript Interpreter");', "doCommand"),
        ('run("JRuby Interpreter");', "Interpreter"),
        ('run("Scr" + "ipt...");', "dynamic command"),
        ('command = "Script..."; run(command);', "dynamic command"),
    ],
)
def test_host_code_primitives_are_rejected(code: str, primitive: str) -> None:
    error = safety.check_macro(code)
    assert error is not None
    assert "host/JVM code primitive" in error
    assert primitive.casefold() in error.casefold()


def test_strings_comments_and_ordinary_imagej_macros_remain_safe() -> None:
    code = r'''
        // exec("python", "-V");
        /* eval("script", "danger");
           Ext.install("danger"); */
        print("call( and IJ.runMacro( are documentation here");
        print("run(\"Script...\") is documentation too");
        run("Gaussian Blur...", "sigma=2");
        run("Descriptor-based registration (2d/3d)");
        setAutoThreshold("Otsu dark");
        run("Convert to Mask");
    '''
    assert safety.check_host_code(code) is None
    assert safety.check_macro(code) is None


def test_unsafe_synchronous_macro_is_rejected_before_fiji(monkeypatch) -> None:
    monkeypatch.setattr(
        tools_fiji,
        "send",
        lambda *args, **kwargs: pytest.fail("unsafe macro reached Fiji"),
    )
    response = tools_fiji.run_macro('call("java.lang.System.exit", "0");')
    assert response["ok"] is False
    assert "host/JVM code primitive" in response["error"]


def test_unsafe_async_macro_is_rejected_before_fiji(monkeypatch) -> None:
    monkeypatch.setattr(
        tools_jobs,
        "send",
        lambda *args, **kwargs: pytest.fail("unsafe macro reached Fiji"),
    )
    response = tools_jobs.run_macro_async('exec("cmd", "/c", "whoami");')
    assert response.startswith("ERROR:")
    assert "host/JVM code primitive" in response
