<?php

declare(strict_types=1);

use Illuminate\Contracts\Console\Kernel;
use Carbon\CarbonImmutable;
use Pterodactyl\Models\Allocation;
use Pterodactyl\Models\Node;
use Pterodactyl\Models\Server;
use Pterodactyl\Models\User;
use Pterodactyl\Repositories\Wings\DaemonPowerRepository;
use Pterodactyl\Services\Allocations\AssignmentService;
use Pterodactyl\Services\Servers\ServerCreationService;

const PANEL_ROOT = '/var/www/pterodactyl';
const EXTERNAL_ID = 'sls-lite-local-velocity';

require PANEL_ROOT . '/vendor/autoload.php';

$app = require PANEL_ROOT . '/bootstrap/app.php';
$app->make(Kernel::class)->bootstrap();

$node = Node::query()->findOrFail(1);
$owner = User::query()->where('username', 'admin')->firstOrFail();

$allocation = Allocation::query()
    ->where('node_id', $node->id)
    ->where('ip', '0.0.0.0')
    ->where('port', 25565)
    ->first();

if (!$allocation) {
    $app->make(AssignmentService::class)->handle($node, [
        'allocation_ip' => '0.0.0.0',
        'allocation_ports' => ['25565'],
        'allocation_alias' => 'localhost',
    ]);

    $allocation = Allocation::query()
        ->where('node_id', $node->id)
        ->where('ip', '0.0.0.0')
        ->where('port', 25565)
        ->firstOrFail();
}

$server = Server::query()->where('external_id', EXTERNAL_ID)->first();
if (!$server) {
    if ($allocation->server_id !== null) {
        throw new RuntimeException('Allocation 0.0.0.0:25565 is already assigned.');
    }

    $server = $app->make(ServerCreationService::class)->handle([
        'external_id' => EXTERNAL_ID,
        'name' => 'SLS-LITE Velocity',
        'description' => 'Local Java 25 Velocity and SLS-LITE integration server.',
        'owner_id' => $owner->id,
        'allocation_id' => $allocation->id,
        'egg_id' => 1,
        'memory' => 6144,
        'swap' => 0,
        'disk' => 20000,
        'io' => 500,
        'cpu' => 400,
        'threads' => null,
        'oom_disabled' => true,
        'startup' => 'java -Xms128M -XX:MaxRAMPercentage=95.0 -jar {{SERVER_JARFILE}}',
        'image' => 'ghcr.io/pterodactyl/yolks:java_25',
        'database_limit' => 0,
        'allocation_limit' => 0,
        'backup_limit' => 2,
        'skip_scripts' => true,
        'start_on_completion' => false,
        'environment' => [
            'BUNGEE_VERSION' => 'latest',
            'SERVER_JARFILE' => 'velocity.jar',
        ],
    ]);
}

$volume = '/var/lib/pterodactyl/volumes/' . $server->uuid;
if ($server->status === Server::STATUS_INSTALLING && is_file($volume . '/velocity.jar')) {
    $server->forceFill([
        'status' => null,
        'installed_at' => CarbonImmutable::now(),
    ])->save();
}

$powerAction = null;
foreach (['start', 'stop', 'restart', 'kill'] as $action) {
    if (in_array('--' . $action, $argv, true)) {
        $powerAction = $action;
        break;
    }
}

if ($powerAction !== null) {
    $app->make(DaemonPowerRepository::class)->setServer($server)->send($powerAction);
}

printf(
    "%s\n",
    json_encode([
        'id' => $server->id,
        'uuid' => $server->uuid,
        'identifier' => $server->uuidShort,
        'status' => $server->status,
        'allocation' => '0.0.0.0:25565',
        'volume' => $volume,
    ], JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES)
);
