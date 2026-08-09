<?php

declare(strict_types=1);

use Illuminate\Contracts\Console\Kernel;
use Pterodactyl\Models\Server;
use Pterodactyl\Repositories\Wings\DaemonPowerRepository;

$panelRoot = getenv('PANEL_ROOT') ?: '/app';
require $panelRoot . '/vendor/autoload.php';
$app = require $panelRoot . '/bootstrap/app.php';
$app->make(Kernel::class)->bootstrap();

$action = $argv[1] ?? '';
if (!in_array($action, ['start', 'stop', 'restart'], true)) {
    fwrite(STDERR, "Usage: php send-power.php <start|stop|restart>\n");
    exit(2);
}

$externalId = getenv('SLS_TEST_SERVER_EXTERNAL_ID') ?: 'sls-lite-local-velocity';
$allowedServers = [
    'sls-lite-local-velocity',
    'sls-lite-local-external-lobby',
];
if (!in_array($externalId, $allowedServers, true)) {
    fwrite(STDERR, "SLS_TEST_SERVER_EXTERNAL_ID is not an allowed local fixture server.\n");
    exit(2);
}

$server = Server::query()
    ->where('external_id', $externalId)
    ->firstOrFail();

$app->make(DaemonPowerRepository::class)
    ->setServer($server)
    ->send($action);

printf("Sent %s to %s\n", $action, $server->uuid);
