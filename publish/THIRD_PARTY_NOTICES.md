# Third-Party Notices

本应用（offScreen）的实现借鉴了以下开源项目的机制。这些项目均以 MIT License 发布，
依照协议要求在此保留其版权声明与许可文本。

## 1. fakeScreen

- 仓库：https://github.com/chuyb-re/fakeScreen
- 借鉴内容：`SurfaceControl.setDisplayPowerMode` 面板断电机制、
  `PhoneWindowManager.powerPress` 电源键拦截思路、display token 分版本获取。

```
MIT License

Copyright (c) 2026 chuyb-re

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## 2. FakeScreen (ANTiqiuSama 维护分支)

- 仓库：https://github.com/ANTiqiuSama/FakeScreen
- 借鉴内容：「先物理操作成功才提交状态」的状态机、多物理屏时经默认屏
  Address 反查内置屏 token、多击/长按放行策略、恢复路径设计。

```
MIT License

Copyright (c) 2026 ANTiqluSama

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## 说明

- offScreen 的代码为独立重写实现（Java / 旧版 Xposed API 82 / 手工构建链），
  未复制上述项目的源码文件。
- 社区项目 betterfakescreen（未公开源码）的状态同步思路在开发中作为参考，
  依照「思想不受版权保护」原则不构成侵权，亦不复制其任何二进制或代码。
