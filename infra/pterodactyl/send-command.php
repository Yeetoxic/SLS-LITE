<?php

declare(strict_types=1);

use Illuminate\Contracts\Console\Kernel;
use Pterodactyl\Models\Server;
use Pterodactyl\Repositories\Wings\DaemonCommandRepository;

$panelRoot = getenv('PANEL_ROOT') ?: '/app';
require $panelRoot . '/vendor/autoload.php';
$app = require $panelRoot . '/bootstrap/app.php';
$app->make(Kernel::class)->bootstrap();

$command = trim(implode(' ', array_slice($argv, 1)));
if ($command === '') {
    fwrite(STDERR, "Usage: php send-command.php <console command>\n");
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

$app->make(DaemonCommandRepository::class)
    ->setServer($server)
    ->send($command);

printf("Sent to %s: %s\n", $server->uuid, $command);
