require('./common');
const FILE_PATH = '/sdcard/Pictures/改密账号.txt';

var username = storage.INSTANCE().get('username', null);
var password = storage.INSTANCE().get('password', null);
var token = null;
var is_running = false;
var task_thread = null;
var pwd = storage.INSTANCE().get('pwd', '登录密码');
var check = storage.INSTANCE().get('check', '是');

if (auto.service == null) { app.startActivity({ action: 'android.settings.ACCESSIBILITY_SETTINGS' }); }
else if (!$floaty.checkPermission()) { $floaty.requestPermission(); }
else { main(); }

function main() {
    if (username && password) { token = api.login(username, password); log('token', token); }
    if (!token) { show_login_ui(); return; }
    show_main_ui();
}
function show_login_ui() {
    var window = floaty.rawWindow(
        <vertical id='root' w='*' h='*' bg='#99222222' padding='10 20'>
            <input id='username' hint='用户名' textColorHint='#FFFFFF' color='#FFFFFF' />
            <input id='password' hint='密码' textColorHint='#FFFFFF' color='#FFFFFF' />
            <text id='login' bg='#AA0984e3' text='登录' textColor='#FFFFFF' gravity='center' marginTop='10' padding='10' />
        </vertical>
    );
    window.requestFocus();
    window.setSize(device.width / 2, dp2px(180));
    window.setPosition(device.width / 4, (device.height - dp2px(180)) / 3);
    window.login.click(function () {
        username = window.username.getText().toString();
        password = window.password.getText().toString();
        token = api.login(username, password);
        if (token) {
            storage.INSTANCE().put('username', username);
            storage.INSTANCE().put('password', password);
            window.close();
            show_main_ui();
        }
    });
}
function show_main_ui() {
    FILES.makesure_file(FILE_PATH);
    var window = floaty.rawWindow(
        <vertical id='root' w='*' h='*'>
            <horizontal id='start_layout' h='40'>
                <text id='start_btn' w='40' h='*' bg='#996699FF' color='#FFFFFF' text='改' gravity='center' textSize='18' />
                <input id='pwd' w='100' color='#FFFFFF' bg='#99222222' gravity='center'></input>
            </horizontal>
            <View w='*' h='2px' bg='#66E7E7E7' />
            <horizontal id='check_layout' h='40'>
                <text id='check_btn' w='40' h='*' bg='#99FF0066' color='#FFFFFF' text='查' gravity='center' textSize='18' />
                <text id='check' w='100' h='*' color='#FFFFFF' bg='#99222222' gravity='center'></text>
            </horizontal>
        </vertical>
    );
    window.requestFocus();
    window.setSize(dp2px(140), dp2px(80));
    window.setPosition(0, (device.height - dp2px(140)));
    window.pwd.setText(pwd);
    window.check.setText(check);
    touch(window, window.start_btn, {
        on_click: function () { if (is_running) { stop(window); return; } start(window, false); },
        on_long_click: function () { exit(); }
    });
    touch(window, window.check_btn, {
        on_click: function () { if (is_running) { stop(window); return; } start(window, true); },
        on_long_click: function () { exit(); }
    });
    touch(window, window.check, {
        on_click: function () { if (check == '是') { check = '否' } else { check = '是'; } window.check.setText(check); },
        on_long_click: function () { exit(); }
    });
    function touch(window, target, callback) {
        let x = 0, y = 0;
        let windowX, windowY;
        let downTime;
        target.setOnTouchListener(function (view, event) {
            switch (event.getAction()) {
                case event.ACTION_DOWN:
                    x = event.getRawX();
                    y = event.getRawY();
                    windowX = window.getX();
                    windowY = window.getY();
                    downTime = new Date().getTime();
                    return true;
                case event.ACTION_MOVE:
                    window.setPosition(windowX + (event.getRawX() - x), windowY + (event.getRawY() - y));
                    return true;
                case event.ACTION_UP:
                    if (Math.abs(windowX - window.getX()) < 10
                        && Math.abs(windowY - window.getY()) < 10) {
                        if (new Date().getTime() - downTime < 600) { callback.on_click(); }
                        if (new Date().getTime() - downTime > 1500) { callback.on_long_click(); }
                    }
                    return true;
            }
            return true;
        });
    }
}
function show_log() {
    const console_log = new function () {
        this.init = function (console_view) {
            console.setGlobalLogConfig({ 'file': '/sdcard/' + context.getPackageName() + '.txt', 'rootLevel': 'ALL', });
            ui.post(function () {
                console_view.setConsole(runtime.console);
                console_view.getChildAt(0).getChildAt(1).visibility = 8;
                let listView = console_view.getChildAt(0).getChildAt(0);
                let log_entries_field = console_view.getClass().getDeclaredField('mLogEntries');
                log_entries_field.setAccessible(true);
                log_entries = log_entries_field.get(console_view);
                listView.setAdapter(console_log.createAdapter(log_entries));
            });
            events.on('log', function () {
                let message = '';
                for (let key in arguments['0']) { message += arguments['0'][key] + ' '; }
                console.info(message);
            });
        }
        this.createAdapter = function (log_entries) {
            return Packages.androidx.recyclerview.widget.RecyclerView.Adapter({
                onCreateViewHolder: function (parent, viewType) {
                    let view = ui.inflate(<horizontal><text w='90' typeface='monospace' /><text id='tv' /></horizontal>, parent, false);
                    let holder = JavaAdapter(Packages.androidx.recyclerview.widget.RecyclerView.ViewHolder, {}, view);
                    return holder;
                },
                onBindViewHolder: function (holder, position) {
                    let log_entry = log_entries.get(position);
                    holder.itemView.getChildAt(0).setTextSize(14);
                    holder.itemView.getChildAt(1).setTextSize(14);
                    holder.itemView.getChildAt(0).setTextColor(colors.parseColor('#00FF00'));
                    holder.itemView.getChildAt(1).setTextColor(colors.parseColor('#00FF00'));
                    holder.itemView.getChildAt(0).setText('[' + new Date().format('hh:mm:ss') + ']');
                    holder.itemView.getChildAt(1).setText(log_entry.content);
                    console_log.set_log_level(log_entry, holder, 4);
                },
                getItemCount: function () {
                    return log_entries.size();
                },
            });
        }
        this.set_log_level = function (log_entry, holder, level) {
            holder.itemView.setVisibility(log_entry.level >= level ? 0 : 8);
        }
    };

    var window = floaty.rawWindow(<vertical id='root' w='*' > <com.stardust.autojs.core.console.ConsoleView id='console' w='*' h='160' bg='#99222222' /> </vertical>);
    window.setSize(device.width, dp2px(160));
    window.setPosition(0, status_bar.height());
    window.setTouchable(false);
    console_log.init(window.console);
    touch(window, window.root);
    function touch(window, target, callback) {
        let x = 0, y = 0;
        let windowX, windowY;
        let downTime;
        target.setOnTouchListener(function (view, event) {
            switch (event.getAction()) {
                case event.ACTION_DOWN:
                    x = event.getRawX();
                    y = event.getRawY();
                    windowX = window.getX();
                    windowY = window.getY();
                    downTime = new Date().getTime();
                    return true;
                case event.ACTION_MOVE:
                    window.setPosition(windowX + (event.getRawX() - x), windowY + (event.getRawY() - y));
                    return true;
                case event.ACTION_UP:
                    if (Math.abs(windowX - window.getX()) < 10
                        && Math.abs(windowY - window.getY()) < 10) {
                        if (new Date().getTime() - downTime < 600) { callback.on_click(); }
                        if (new Date().getTime() - downTime > 1500) { callback.on_long_click(); }
                    }
                    return true;
            }
            return true;
        });
    }
}
function stop(window) {
    if (task_thread) { task_thread.interrupt(); }
    window.requestFocus();
    window.setSize(dp2px(140), dp2px(80));
    window.start_btn.setText('改');
    window.check_btn.setText('查');
    is_running = false;
}
function start(window, check) {
    is_running = true;
    window.setSize(dp2px(40), dp2px(80));
    window.disableFocus();
    if (check) {
        window.check_btn.setText('▉');
        task_thread = threads.start(function () {
            show_log();
            task_check(null);
            ui.post(function () { stop(window); });
        });
    } else {
        window.start_btn.setText('▉');
        pwd = window.pwd.getText().toString();
        check = window.check.getText().toString();
        if (pwd == '登录密码') { toastLog('请输入登录密码'); stop(window); return; }
        storage.INSTANCE().put('pwd', pwd);
        storage.INSTANCE().put('check', check);
        task_thread = threads.start(function () {
            show_log();
            task_modify(check == '是');
            ui.post(function () { stop(window); });
        });
    }
}
function task_modify(check) {
    while (true) {
        sleep(1000);
        sandbox.remove_all();
        let account = api.get_account();
        if (!account) { bus.log('[无账号]'); break; }
        bus.log('[开始改密]' + '[' + account.user_name + ']');
        sandbox2.start(account.user_name);
        sleep(1000);
        let login_result = sign_in(account);
        api.更新用户状态(account, login_result);
        if (login_result == api.死号 || login_result == api.无状态) { continue; }
        let reset_result = reset_pwd(account, pwd);
        if (!reset_result) { bus.log('[重置登录密码失败]' + '[' + account.user_name + ']'); continue; }
        FILES.append_line(FILE_PATH, account.user_name + '|' + pwd)
        bus.log('[重置登录密码成功]' + '[' + account.user_name + ']');
        if (!check) { continue; }
        if (!task_check(account)) break;
    }
    function reset_pwd(account, pwd) {
        app.startActivity({
            className: 'com.alipay.android.widget.security.ui.PasswordSettingActivity_',
            packageName: 'com.eg.android.AlipayGphone',
            flags: ['activity_new_task'],
            extras: { 'app_id': '20000801', },
            root: true
        });
        while (true) {
            sleep(1000);
            if (random.random_click(desc('重置登录密码').clickable().findOnce())) { log('重置登录密码'); continue; }
            if (random.random_click(text('记得').clickable().findOnce())) { log('记得'); continue; }
            if (random.random_set_text(id('currentPassword').findOnce(), account.password)) {
                log('输入密码', account.password);
                sleep(1000);
                if (random.random_click(text('下一步').clickable().findOnce())) { log('下一步'); continue; }
            }
            if (random.random_set_text(id('newPassword').findOnce(), pwd)) {
                log('输入新密码', pwd);
                sleep(1000);
                if (random.random_click(text('保存新密码').clickable().findOnce())) { log('保存新密码'); return true; }
            }
        }
    }
}
function task_check(account) {
    if (account) return check(account);
    while (true) {
        sleep(1000);
        sandbox.remove_all();
        let account = api.get_account();
        if (!account) { bus.log('[无账号]'); break; }
        bus.log('[开始检查]' + '[' + account.user_name + ']');
        sandbox2.start(account.user_name);
        sleep(1000);
        let login_result = sign_in(account);
        api.更新用户状态(account, login_result);
        if (login_result == api.死号 || login_result == api.无状态) { continue; }
        if (!check(account)) { break; }
    }
    function check(account) {
        let group = api.get_params_group();
        if (!group) { bus.log('[无付款参数]'); return false; }
        let param = group[0];
        bus.log('[开始转换]' + '[' + param.ID + ']');
        let try_times = 2;
        let result = api.死号;
        for (let i = 0; i < try_times; i++) {
            let convert_result = convert(param);
            bus.log('[转换结果]', '[' + (convert_result ? '成功' : '失败') + ']');
            if (!convert_result) continue;
            result = api.已登录;
            break;
        }
        if (result == api.死号) 恢复参数(param, 0);
        api.更新用户状态(account, result);
        return true;
    }
    function convert(param) {
        app.startActivity({ packageName: 'com.douyu.duoduopay', className: 'com.douyu.duoduopay.Activity.MainActivity', extras: { 'params': param.params } });
        sleep(3000);
        while (true) {
            sleep(2000);
            if (text('找朋友帮忙付').exists() || text('立即付款').exists()) { return true; }
            if (random.random_click(text('确定').findOnce())) { log('确定'); return false; };// 有 确定 全部定义为 死号
        }
    }
    function 恢复参数(params, state) { params.state = state; api.update_params(params); }
}


function sign_in(account) {
    while (true) {
        sleep(1000);
        if (text('身份验证').exists() && text('输入短信验证码').exists()) { log('身份验证', '输入短信验证码'); return api.无状态; }
        if (textStartsWith('此账号尚未设置登录密码').exists()) { log('此账号尚未设置登录密码'); back(); return api.死号; }
        if (text('绑定本人中国大陆银行卡验证身份').exists()) { log('绑定本人中国大陆银行卡验证身份'); return api.死号; }
        if (text('请填写你的真实信息，通过后不能更改').exists()) { log('请填写你的真实信息，通过后不能更改'); return api.死号; }
        if (text('拨打').exists()) { log('拨打'); return api.死号; }
        if (text('重置密码').exists()) { log('重置密码'); return api.死号; }
        if (text('忘记密码？').exists()) {
            if (textStartsWith('账号或登录密码错误').exists() &&
                random.random_click(text('重新输入').findOnce())) { log('账号或登录密码错误'); return api.死号; }
            let inputs = className('android.widget.EditText').find();
            if (!inputs || inputs.size() != 2) { continue; }
            random.random_set_text(inputs.get(0), account.user_name);
            sleep(300);
            random.random_set_text(inputs.get(0), account.user_name);
            sleep(300);
            random.random_set_text(inputs.get(1), account.password);
            sleep(300);
            random.random_click(text('登录').clickable().findOnce());
            sleep(3000);
            continue;
        }
        if (text('登录').clickable().exists()) { text('登录').clickable().findOnce().click(); sleep(3000); continue; }
        if (text('允许').exists()) { log('允许'); apps.restart('com.eg.android.AlipayGphone', id('alipay_home_view')); continue; };
        if (text('跳过').clickable().exists()) { log('跳过'); apps.restart('com.eg.android.AlipayGphone', id('alipay_home_view')); continue; };
        if (text('稍后再说').clickable().exists()) { log('稍后再说'); apps.restart('com.eg.android.AlipayGphone', id('alipay_home_view')); continue; };
        if (text('下一步').exists()) { log('下一步'); apps.restart('com.eg.android.AlipayGphone', id('alipay_home_view')); continue; }
        if (text('扫一扫').exists()) { log('登录成功'); return api.已登录; }
    }
}

setInterval(() => { }, 1000);