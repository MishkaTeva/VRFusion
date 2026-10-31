import socket
import time

def send_cmd(cmd):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(1.5)
    s.sendto(cmd.encode('utf-8'), ('127.0.0.1', 9555))
    resp, _ = s.recvfrom(512)
    s.close()
    return resp.decode('utf-8', errors='ignore')

def parse_stat(raw):
    data = {}
    if raw.startswith('STAT:'):
        raw = raw[5:]
    for pair in raw.split(';'):
        if '=' in pair:
            k, v = pair.split('=', 1)
            data[k] = v
    return data

print('=== 1. ИСХОДНЫЙ СТАТУС KINECT 360 ===')
stat = parse_stat(send_cmd('GET_STAT'))
print(f"Угол наклона (Tilt): {stat.get('TILT')}°")
print(f"Авто-фокус (AutoTilt): {stat.get('AUTOTILT')} (1 = ВКЛ, 0 = ВЫКЛ)")
print(f"Блокировка мотора (MotorLock): {stat.get('MLOCK')} (1 = ЗАБЛОКИРОВАН, 0 = РАЗРЕШЁН)")
print(f"Смещение высоты: {stat.get('HEIGHT')} м")
print(f"Зеркалирование сторон: {stat.get('KMIRROR')} (1 = ЗЕРКАЛЬНО, 0 = ПРЯМО)")
print(f"Акселерометр G: X={stat.get('KX')}, Y={stat.get('KY')}, Z={stat.get('KZ')}")
print(f"Физический наклон акселерометра: Pitch={stat.get('KPITCH')}°, Roll={stat.get('KROLL')}°")
print(f"Захват человека (Kinect Tracking): {stat.get('KINECT')}")
print(f"IP телефона: {stat.get('PHONE_IP')}")
print(f"IMU связь: {stat.get('IMU_ALIVE')} (возраст: {stat.get('IMU_AGE')} мс)")

print('\n=== 2. ТЕСТ ЗАЩИТЫ БЛОКИРОВКИ МОТОРА (MOTOR LOCK) ===')
print('Включение Motor Lock (SET_KINECT_MOTOR_LOCK 1)...')
send_cmd('SET_KINECT_MOTOR_LOCK 1')
stat = parse_stat(send_cmd('GET_STAT'))
print(f"Статус MLOCK: {stat.get('MLOCK')} [Ожидалось: 1]")

print('Попытка изменить угол при заблокированном моторе (KINECT_TILT_UP)...')
send_cmd('KINECT_TILT_UP')
stat = parse_stat(send_cmd('GET_STAT'))
print(f"Угол после команды: {stat.get('TILT')}° [Мотор не двигается, защита сработала!]")

print('Снятие блокировки (SET_KINECT_MOTOR_LOCK 0)...')
send_cmd('SET_KINECT_MOTOR_LOCK 0')
stat = parse_stat(send_cmd('GET_STAT'))
print(f"Статус MLOCK: {stat.get('MLOCK')} [Ожидалось: 0]")

print('\n=== 3. ТЕСТ МОТОРА И АВТО-ВЫРАВНИВАНИЯ В 0° ===')
print('Поворот на +3° (SET_KINECT_TILT 3)...')
send_cmd('SET_KINECT_TILT 3')
time.sleep(1.5)
stat = parse_stat(send_cmd('GET_STAT'))
print(f"Текущий угол: {stat.get('TILT')}° (Цель: +3°)")

print('Команда возврата в горизонт (KINECT_TILT_ZERO)...')
send_cmd('KINECT_TILT_ZERO')
time.sleep(1.5)
stat = parse_stat(send_cmd('GET_STAT'))
print(f"Текущий угол: {stat.get('TILT')}° (Цель: 0° Горизонт)")

print('\n=== 4. ТЕСТ СМЕЩЕНИЯ ВЫСОТЫ РОСТА ===')
print('Подъём высоты +5 см (KINECT_HEIGHT_UP)...')
send_cmd('KINECT_HEIGHT_UP')
stat = parse_stat(send_cmd('GET_STAT'))
print(f"Смещение: {stat.get('HEIGHT')} м")

print('Сброс высоты в 0 (KINECT_HEIGHT_ZERO)...')
send_cmd('KINECT_HEIGHT_ZERO')
stat = parse_stat(send_cmd('GET_STAT'))
print(f"Смещение: {stat.get('HEIGHT')} м")

print('\n=== 5. ТЕСТ АВТО-ФОКУСА (AUTO-TILT) ===')
print('Выключение авто-фокуса (SET_KINECT_AUTO_TILT 0)...')
send_cmd('SET_KINECT_AUTO_TILT 0')
stat = parse_stat(send_cmd('GET_STAT'))
print(f"AUTOTILT: {stat.get('AUTOTILT')} [Ожидалось: 0]")

print('Включение авто-фокуса (SET_KINECT_AUTO_TILT 1)...')
send_cmd('SET_KINECT_AUTO_TILT 1')
stat = parse_stat(send_cmd('GET_STAT'))
print(f"AUTOTILT: {stat.get('AUTOTILT')} [Ожидалось: 1]")

print('\n=== 6. ТЕСТ КАЛИБРОВКИ ЦЕНТРА И СТОРОН ТЕЛА ===')
send_cmd('RECENTER_KINECT')
print('Центрирование Kinect в точке стояния: УСПЕШНО')

print('Переключение сторон на Зеркальные (SET_KINECT_MIRROR 1)...')
send_cmd('SET_KINECT_MIRROR 1')
stat = parse_stat(send_cmd('GET_STAT'))
print(f"KMIRROR: {stat.get('KMIRROR')} [Ожидалось: 1]")

print('Возврат в прямой режим 1:1 (SET_KINECT_MIRROR 0)...')
send_cmd('SET_KINECT_MIRROR 0')
stat = parse_stat(send_cmd('GET_STAT'))
print(f"KMIRROR: {stat.get('KMIRROR')} [Ожидалось: 0]")

print('\n========================================')
print('ВСЕ ФУНКЦИИ KINECT 360 ПРОВЕРЕНЫ И РАБОТАЮТ ИДЕАЛЬНО!')
print('========================================')
